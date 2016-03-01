// Copyright 2016 Google Inc. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.domain.registry.rde;

import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;
import static com.google.common.base.Verify.verify;
import static com.google.common.net.MediaType.PLAIN_TEXT_UTF_8;
import static com.google.domain.registry.model.ofy.ObjectifyService.ofy;
import static com.google.domain.registry.model.rde.RdeMode.FULL;
import static com.google.domain.registry.request.Action.Method.POST;
import static com.google.domain.registry.util.DateTimeUtils.START_OF_TIME;
import static com.jcraft.jsch.ChannelSftp.OVERWRITE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.tools.cloudstorage.GcsFilename;
import com.google.common.io.ByteStreams;
import com.google.domain.registry.config.ConfigModule.Config;
import com.google.domain.registry.gcs.GcsUtils;
import com.google.domain.registry.keyring.api.KeyModule.Key;
import com.google.domain.registry.model.rde.RdeNamingUtils;
import com.google.domain.registry.model.rde.RdeRevision;
import com.google.domain.registry.model.registry.Registry;
import com.google.domain.registry.model.registry.RegistryCursor;
import com.google.domain.registry.model.registry.RegistryCursor.CursorType;
import com.google.domain.registry.rde.EscrowTaskRunner.EscrowTask;
import com.google.domain.registry.rde.JSchSshSession.JSchSshSessionFactory;
import com.google.domain.registry.request.Action;
import com.google.domain.registry.request.HttpException.ServiceUnavailableException;
import com.google.domain.registry.request.Parameter;
import com.google.domain.registry.request.RequestParameters;
import com.google.domain.registry.request.Response;
import com.google.domain.registry.util.Clock;
import com.google.domain.registry.util.FormattingLogger;
import com.google.domain.registry.util.TaskEnqueuer;
import com.google.domain.registry.util.TeeOutputStream;

import com.googlecode.objectify.VoidWork;
import com.jcraft.jsch.JSch;

import org.bouncycastle.openpgp.PGPKeyPair;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPPublicKey;
import org.joda.time.DateTime;
import org.joda.time.Duration;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Task that securely uploads an RDE XML file from Cloud Storage to a trusted third party (such as
 * Iron Mountain) via SFTP.
 *
 * <p>This task is invoked by {@link RdeStagingAction} once it's created the files we need. The date
 * is calculated from {@link CursorType#RDE_UPLOAD}.
 *
 * <p>Once this task completes, it rolls the cursor forward a day and triggers
 * {@link RdeReportTask}.
 */
@Action(path = RdeUploadTask.PATH, method = POST)
public final class RdeUploadTask implements Runnable, EscrowTask {

  static final String PATH = "/_dr/task/rdeUpload";

  private static final FormattingLogger logger = FormattingLogger.getLoggerForCallerClass();

  @Inject Clock clock;
  @Inject GcsUtils gcsUtils;
  @Inject Ghostryde ghostryde;
  @Inject EscrowTaskRunner runner;
  @Inject JSch jsch;
  @Inject JSchSshSessionFactory jschSshSessionFactory;
  @Inject Response response;
  @Inject RydePgpCompressionOutputStreamFactory pgpCompressionFactory;
  @Inject RydePgpEncryptionOutputStreamFactory pgpEncryptionFactory;
  @Inject RydePgpFileOutputStreamFactory pgpFileFactory;
  @Inject RydePgpSigningOutputStreamFactory pgpSigningFactory;
  @Inject RydeTarOutputStreamFactory tarFactory;
  @Inject TaskEnqueuer taskEnqueuer;
  @Inject @Parameter(RequestParameters.PARAM_TLD) String tld;
  @Inject @Config("rdeBucket") String bucket;
  @Inject @Config("rdeInterval") Duration interval;
  @Inject @Config("rdeUploadLockTimeout") Duration timeout;
  @Inject @Config("rdeUploadSftpCooldown") Duration sftpCooldown;
  @Inject @Config("rdeUploadUrl") URI uploadUrl;
  @Inject @Key("rdeReceiverKey") PGPPublicKey receiverKey;
  @Inject @Key("rdeSigningKey") PGPKeyPair signingKey;
  @Inject @Key("rdeStagingDecryptionKey") PGPPrivateKey stagingDecryptionKey;
  @Inject @Named("rde-report") Queue reportQueue;
  @Inject RdeUploadTask() {}

  @Override
  public void run() {
    runner.lockRunAndRollForward(this, Registry.get(tld), timeout, CursorType.RDE_UPLOAD, interval);
    taskEnqueuer.enqueue(
        reportQueue,
        withUrl(RdeReportTask.PATH).param(RequestParameters.PARAM_TLD, tld));
  }

  @Override
  public void runWithLock(DateTime watermark) throws Exception {
    DateTime stagingCursor =
        RegistryCursor.load(Registry.get(tld), CursorType.RDE_STAGING).or(START_OF_TIME);
    if (!stagingCursor.isAfter(watermark)) {
      logger.infofmt("tld=%s uploadCursor=%s stagingCursor=%s", tld, watermark, stagingCursor);
      throw new ServiceUnavailableException("Waiting for RdeStagingTask to complete");
    }
    DateTime sftpCursor =
        RegistryCursor.load(Registry.get(tld), CursorType.RDE_UPLOAD_SFTP).or(START_OF_TIME);
    if (sftpCursor.plus(sftpCooldown).isAfter(clock.nowUtc())) {
      // Fail the task good and hard so it retries until the cooldown passes.
      logger.infofmt("tld=%s cursor=%s sftpCursor=%s", tld, watermark, sftpCursor);
      throw new ServiceUnavailableException("SFTP cooldown has not yet passed");
    }
    int revision = RdeRevision.getNextRevision(tld, watermark, FULL) - 1;
    verify(revision >= 0, "RdeRevision was not set on generated deposit");
    String name = RdeNamingUtils.makeRydeFilename(tld, watermark, FULL, 1, revision);
    GcsFilename xmlFilename = new GcsFilename(bucket, name + ".xml.ghostryde");
    GcsFilename xmlLengthFilename = new GcsFilename(bucket, name + ".xml.length");
    GcsFilename reportFilename = new GcsFilename(bucket, name + "-report.xml.ghostryde");
    verifyFileExists(xmlFilename);
    verifyFileExists(xmlLengthFilename);
    verifyFileExists(reportFilename);
    upload(xmlFilename, readXmlLength(xmlLengthFilename), watermark, name);
    ofy().transact(new VoidWork() {
      @Override
      public void vrun() {
        RegistryCursor.save(
            Registry.get(tld), CursorType.RDE_UPLOAD_SFTP, ofy().getTransactionTime());
      }
    });
    response.setContentType(PLAIN_TEXT_UTF_8);
    response.setPayload(String.format("OK %s %s\n", tld, watermark));
  }

  /**
   * Performs a blocking upload of a cloud storage XML file to escrow provider, converting
   * it to the RyDE format along the way by applying tar+compress+encrypt+sign, and saving the
   * created RyDE file on GCS for future reference.
   *
   * <p>This is done by layering a bunch of {@link java.io.FilterOutputStream FilterOutputStreams}
   * on top of each other in reverse order that turn XML bytes into a RyDE file while
   * simultaneously uploading it to the SFTP endpoint, and then using {@link ByteStreams#copy} to
   * blocking-copy bytes from the cloud storage {@code InputStream} to the RyDE/SFTP pipeline.
   *
   * <p>In psuedoshell, the whole process looks like the following:
   *
   * <pre>   {@code
   *   gcs read $xmlFile \                                   # Get GhostRyDE from cloud storage.
   *     | decrypt | decompress \                            # Convert it to XML.
   *     | tar | file | compress | encrypt | sign /tmp/sig \ # Convert it to a RyDE file.
   *     | tee gs://bucket/$rydeFilename.ryde \              # Save a copy of the RyDE file to GCS.
   *     | sftp put $dstUrl/$rydeFilename.ryde \             # Upload to SFTP server.
   *    && sftp put $dstUrl/$rydeFilename.sig </tmp/sig \    # Upload detached signature.
   *    && cat /tmp/sig > gs://bucket/$rydeFilename.sig      # Save a copy of signature to GCS.
   *   }</pre>
   */
  private void upload(
      GcsFilename xmlFile, long xmlLength, DateTime watermark, String name) throws Exception {
    logger.infofmt("Uploading %s to %s", xmlFile, uploadUrl);
    try (InputStream gcsInput = gcsUtils.openInputStream(xmlFile);
        Ghostryde.Decryptor decryptor = ghostryde.openDecryptor(gcsInput, stagingDecryptionKey);
        Ghostryde.Decompressor decompressor = ghostryde.openDecompressor(decryptor);
        Ghostryde.Input xmlInput = ghostryde.openInput(decompressor)) {
      try (JSchSshSession session = jschSshSessionFactory.create(jsch, uploadUrl);
          JSchSftpChannel ftpChan = session.openSftpChannel()) {
        byte[] signature;
        String rydeFilename = name + ".ryde";
        GcsFilename rydeGcsFilename = new GcsFilename(bucket, rydeFilename);
        try (OutputStream ftpOutput = ftpChan.get().put(rydeFilename, OVERWRITE);
            OutputStream gcsOutput = gcsUtils.openOutputStream(rydeGcsFilename);
            TeeOutputStream teeOutput = new TeeOutputStream(asList(ftpOutput, gcsOutput));
            RydePgpSigningOutputStream signer = pgpSigningFactory.create(teeOutput, signingKey)) {
          try (OutputStream encryptLayer = pgpEncryptionFactory.create(signer, receiverKey);
              OutputStream kompressor = pgpCompressionFactory.create(encryptLayer);
              OutputStream fileLayer = pgpFileFactory.create(kompressor, watermark, name + ".tar");
              OutputStream tarLayer =
                  tarFactory.create(fileLayer, xmlLength, watermark, name + ".xml")) {
            ByteStreams.copy(xmlInput, tarLayer);
          }
          signature = signer.getSignature();
          logger.infofmt("uploaded %,d bytes: %s.ryde", signer.getBytesWritten(), name);
        }
        String sigFilename = name + ".sig";
        gcsUtils.createFromBytes(new GcsFilename(bucket, sigFilename), signature);
        ftpChan.get().put(new ByteArrayInputStream(signature), sigFilename);
        logger.infofmt("uploaded %,d bytes: %s.sig", signature.length, name);
      }
    }
  }

  /** Reads the contents of a file from Cloud Storage that contains nothing but an integer. */
  private long readXmlLength(GcsFilename xmlLengthFilename) throws IOException {
    try (InputStream input = gcsUtils.openInputStream(xmlLengthFilename)) {
      return Long.parseLong(new String(ByteStreams.toByteArray(input), UTF_8).trim());
    }
  }

  private void verifyFileExists(GcsFilename filename) {
    verify(gcsUtils.existsAndNotEmpty(filename), "Missing file: %s", filename);
  }
}
