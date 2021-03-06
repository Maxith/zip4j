package net.lingala.zip4j.io.outputstream;

import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.headers.FileHeaderFactory;
import net.lingala.zip4j.headers.HeaderSignature;
import net.lingala.zip4j.headers.HeaderWriter;
import net.lingala.zip4j.model.FileHeader;
import net.lingala.zip4j.model.LocalFileHeader;
import net.lingala.zip4j.model.ZipModel;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.util.Raw;
import net.lingala.zip4j.zip.CompressionMethod;
import net.lingala.zip4j.zip.EncryptionMethod;

import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;

public class ZipOutputStream extends OutputStream {

  private CountingOutputStream countingOutputStream;
  private char[] password;
  private ZipModel zipModel;
  private CompressedOutputStream compressedOutputStream;
  private FileHeader fileHeader;
  private LocalFileHeader localFileHeader;
  private FileHeaderFactory fileHeaderFactory = new FileHeaderFactory();
  private HeaderWriter headerWriter = new HeaderWriter();
  private CRC32 crc32 = new CRC32();
  private long uncompressedSizeForThisEntry = 0;
  private boolean writeCrc32 = true;

  public ZipOutputStream(OutputStream outputStream) throws IOException {
    this(outputStream, null);
  }

  public ZipOutputStream(OutputStream outputStream, char[] password) throws IOException {
    this(outputStream, password, new ZipModel());
  }

  public ZipOutputStream(OutputStream outputStream, char[] password, ZipModel zipModel) throws IOException {
    this.countingOutputStream = new CountingOutputStream(outputStream);
    this.password = password;
    this.zipModel = initializeZipModel(zipModel, countingOutputStream);
    writeSplitZipHeaderIfApplicable();
  }

  public void putNextEntry(ZipParameters zipParameters) throws IOException {
    try {
      verifyZipParameters(zipParameters);
      initializeAndWriteFileHeader(zipParameters);

      //Initialisation of below compressedOutputStream should happen after writing local file header
      //because local header data should be written first and then the encryption header data
      //and below initialisation writes encryption header data
      compressedOutputStream = initializeCompressedOutputStream(zipParameters);

      if (zipParameters.isEncryptFiles() && zipParameters.getEncryptionMethod() == EncryptionMethod.AES) {
        this.writeCrc32 = false;
      }
    } catch (IOException e) {
      throw e;
    } catch (ZipException e) {
      throw new IOException(e);
    }
  }

  public void write(int b) throws IOException {
    write(new byte[] {(byte)b});
  }

  public void write(byte[] b) throws IOException {
    write(b, 0, b.length);
  }

  public void write(byte[] b, int off, int len) throws IOException {
    crc32.update(b, off, len);
    compressedOutputStream.write(b, off, len);
    uncompressedSizeForThisEntry += len;
  }

  public void closeEntry() throws IOException {
    try {
      compressedOutputStream.closeEntry();

      long compressedSize = compressedOutputStream.getCompressedSize();
      fileHeader.setCompressedSize(compressedSize);
      localFileHeader.setCompressedSize(compressedSize);

      fileHeader.setUncompressedSize(uncompressedSizeForThisEntry);
      localFileHeader.setUncompressedSize(uncompressedSizeForThisEntry);

      if (writeCrc32) {
        fileHeader.setCrc32(crc32.getValue());
        localFileHeader.setCrc32(crc32.getValue());
      }

      zipModel.getLocalFileHeaders().add(localFileHeader);
      zipModel.getCentralDirectory().getFileHeaders().add(fileHeader);

      headerWriter.writeExtendedLocalHeader(localFileHeader, countingOutputStream);

      reset();
    } catch (ZipException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      zipModel.getEndOfCentralDirectoryRecord().setOffsetOfStartOfCentralDirectory(countingOutputStream.getNumberOfBytesWritten());
      headerWriter.finalizeZipFile(zipModel, countingOutputStream);
    } catch (ZipException e) {
      throw new IOException(e);
    }

    countingOutputStream.close();
  }

  private ZipModel initializeZipModel(ZipModel zipModel, CountingOutputStream countingOutputStream) {
    if (zipModel == null) {
      zipModel = new ZipModel();
    }

    if (countingOutputStream.isSplitOutputStream()) {
      zipModel.setSplitArchive(true);
      zipModel.setSplitLength(countingOutputStream.getSplitLength());
    }

    return zipModel;
  }

  private void initializeAndWriteFileHeader(ZipParameters zipParameters) throws ZipException, IOException {
    fileHeader = fileHeaderFactory.generateFileHeader(zipParameters, countingOutputStream.isSplitOutputStream(),
        countingOutputStream.getCurrentSplitFileCounter(), zipModel.getFileNameCharset());
    fileHeader.setOffsetLocalHeader(countingOutputStream.getOffsetForNextEntry());

    localFileHeader = fileHeaderFactory.generateLocalFileHeader(fileHeader);
    headerWriter.writeLocalFileHeader(zipModel, localFileHeader, countingOutputStream);
  }

  private void reset() throws IOException {
    uncompressedSizeForThisEntry = 0;
    crc32.reset();
    compressedOutputStream.close();
  }

  private void writeSplitZipHeaderIfApplicable() throws IOException {
    if (!countingOutputStream.isSplitOutputStream()) {
      return;
    }

    byte[] intByte = new byte[4];
    Raw.writeIntLittleEndian(intByte, 0, (int) HeaderSignature.SPLIT_ZIP.getValue());
    countingOutputStream.write(intByte);
  }

  private CompressedOutputStream initializeCompressedOutputStream(ZipParameters zipParameters) throws IOException, ZipException {
    ZipEntryOutputStream zipEntryOutputStream = new ZipEntryOutputStream(countingOutputStream);
    CipherOutputStream cipherOutputStream = initializeCipherOutputStream(zipEntryOutputStream, zipParameters);
    return initializeCompressedOutputStream(cipherOutputStream, zipParameters);
  }

  private CipherOutputStream initializeCipherOutputStream(ZipEntryOutputStream zipEntryOutputStream, ZipParameters zipParameters) throws IOException, ZipException {
    if (!zipParameters.isEncryptFiles()) {
      return new NoCipherOutputStream(zipEntryOutputStream, zipParameters, null);
    }

    if (password == null || password.length == 0) {
      throw new ZipException("password not set");
    }

    if (zipParameters.getEncryptionMethod() == EncryptionMethod.AES) {
      return new AesCipherOutputStream(zipEntryOutputStream, zipParameters, password);
    } else if (zipParameters.getEncryptionMethod() == EncryptionMethod.ZIP_STANDARD) {
      return new ZipStandardCipherOutputStream(zipEntryOutputStream, zipParameters, password);
    } else {
      throw new ZipException("Invalid encryption method");
    }
  }

  private CompressedOutputStream initializeCompressedOutputStream(CipherOutputStream cipherOutputStream, ZipParameters zipParameters) {
    if (zipParameters.getCompressionMethod() == CompressionMethod.DEFLATE) {
      return new DeflaterOutputStream(cipherOutputStream, zipParameters.getCompressionLevel());
    }

    return new StoreOutputStream(cipherOutputStream);
  }

  private void verifyZipParameters(ZipParameters zipParameters) {
    if (zipParameters.getCompressionMethod() == CompressionMethod.STORE
        && zipParameters.getUncompressedSize() == 0
        && !isEntryDirectory(zipParameters.getFileNameInZip())) {
      throw new IllegalArgumentException("uncompressed size should be set for zip entries of compression type store");
    }
  }

  private boolean isEntryDirectory(String entryName) {
    return entryName.endsWith("/") || entryName.endsWith("\\");
  }
}
