package net.lingala.zip4j.io.inputstream;

import net.lingala.zip4j.crypto.AESDecrypter;
import net.lingala.zip4j.crypto.Decrypter;
import net.lingala.zip4j.exception.ZipException;
import net.lingala.zip4j.model.AESExtraDataRecord;
import net.lingala.zip4j.model.LocalFileHeader;

import java.io.IOException;
import java.io.InputStream;

import static net.lingala.zip4j.util.InternalZipConstants.AES_AUTH_LENGTH;

class AesCipherInputStream extends CipherInputStream {

  private byte[] storedMac = new byte[AES_AUTH_LENGTH];
  private byte[] aesBlockByte = new byte[16];
  private int aesBytesReturned = 0;
  private boolean non16ByteBlockRead = false;

  public AesCipherInputStream(ZipEntryInputStream zipEntryInputStream, LocalFileHeader localFileHeader, char[] password) throws IOException, ZipException {
    super(zipEntryInputStream, localFileHeader, password);
  }

  @Override
  protected Decrypter initializeDecrypter(LocalFileHeader localFileHeader, char[] password) throws IOException, ZipException {
    return new AESDecrypter(localFileHeader.getAesExtraDataRecord(), password, getSalt(localFileHeader), getPasswordVerifier());
  }

  @Override
  public int read() throws IOException {
    if (aesBytesReturned == 0 || aesBytesReturned == 16) {
      if (read(aesBlockByte) == -1) {
        return -1;
      }
      aesBytesReturned = 0;
    }
    return aesBlockByte[aesBytesReturned++] & 0xff;
  }

  @Override
  public int read(byte[] b) throws IOException {
    return read(b, 0, b.length);
  }

  @Override
  public int read(byte[] b, int off, int len) throws IOException {
    assertNon16ByteBlockNotReadTwice(len);
    return super.read(b, off, len);
  }

  @Override
  protected void endOfEntryReached(InputStream inputStream) throws IOException {
    readMac(inputStream);
  }

  protected void readMac(InputStream inputStream) throws IOException {
    int readLen = inputStream.read(storedMac);

    if (readLen != AES_AUTH_LENGTH) {
      throw new IOException("Invalid AES Mac bytes. Could not read sufficient data");
    }
  }

  private void assertNon16ByteBlockNotReadTwice(int readLen) throws IOException {
    if (readLen % 16 != 0) {
      if (non16ByteBlockRead) {
        throw new IOException("AES non-16 byte block already read");
      }
      non16ByteBlockRead = true;
    }
  }

  private byte[] getSalt(LocalFileHeader localFileHeader) throws IOException {
    if (localFileHeader.getAesExtraDataRecord() == null) {
      throw new IOException("invalid aes extra data record");
    }

    AESExtraDataRecord aesExtraDataRecord = localFileHeader.getAesExtraDataRecord();
    byte[] saltBytes = new byte[aesExtraDataRecord.getAesKeyStrength().getSaltLength()];
    readRaw(saltBytes);
    return saltBytes;
  }

  private byte[] getPasswordVerifier() throws IOException {
    byte[] pvBytes = new byte[2];
    readRaw(pvBytes);
    return pvBytes;
  }
}
