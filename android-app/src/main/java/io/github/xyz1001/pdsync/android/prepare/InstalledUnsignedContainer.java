package io.github.xyz1001.pdsync.android.prepare;

import com.android.apksig.apk.ApkUtils;
import com.android.apksig.internal.zip.CentralDirectoryRecord;
import com.android.apksig.internal.zip.LocalFileRecord;
import com.android.apksig.util.DataSource;
import com.android.apksig.util.DataSources;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.regex.Pattern;

final class InstalledUnsignedContainer {
    private static final Pattern V1_ARTIFACT = Pattern.compile("META-INF/(?:MANIFEST\\.MF|[^/]+\\.(?:SF|RSA|DSA|EC)|SIG-[^/]+)", 2);

    private InstalledUnsignedContainer() {
    }

    static void requireAbsentSigningBlock(File file) throws IOException {
        try {
            RandomAccessFile randomAccessFile = new RandomAccessFile(file, "r");
            try {
                DataSource dataSourceAsDataSource = DataSources.asDataSource(randomAccessFile);
                ApkUtils.ZipSections zipSectionsFindZipSections = ApkUtils.findZipSections(dataSourceAsDataSource);
                long zipCentralDirectoryOffset = zipSectionsFindZipSections.getZipCentralDirectoryOffset();
                if (zipSectionsFindZipSections.getZipCentralDirectorySizeBytes() + zipCentralDirectoryOffset != zipSectionsFindZipSections.getZipEndOfCentralDirectoryOffset()) {
                    throw new IOException("Installed source has an ambiguous ZIP signing container");
                }
                long zipCentralDirectorySizeBytes = zipSectionsFindZipSections.getZipCentralDirectorySizeBytes();
                int zipCentralDirectoryRecordCount = zipSectionsFindZipSections.getZipCentralDirectoryRecordCount();
                if (zipCentralDirectoryRecordCount >= 1 && zipCentralDirectoryRecordCount <= 100000) {
                    long startOffsetInArchive = 0;
                    if (zipCentralDirectorySizeBytes >= 0 && zipCentralDirectorySizeBytes <= 2147483647L) {
                        ByteBuffer byteBufferOrder = dataSourceAsDataSource.getByteBuffer(zipCentralDirectoryOffset, (int) zipCentralDirectorySizeBytes).order(ByteOrder.LITTLE_ENDIAN);
                        ArrayList<CentralDirectoryRecord> arrayList = new ArrayList();
                        for (int i = 0; i < zipCentralDirectoryRecordCount; i++) {
                            arrayList.add(CentralDirectoryRecord.getRecord(byteBufferOrder));
                        }
                        if (byteBufferOrder.hasRemaining()) {
                            throw new IOException("Installed source has ambiguous ZIP signing directory data");
                        }
                        arrayList.sort(CentralDirectoryRecord.BY_LOCAL_FILE_HEADER_OFFSET_COMPARATOR);
                        for (CentralDirectoryRecord centralDirectoryRecord : arrayList) {
                            if (V1_ARTIFACT.matcher(centralDirectoryRecord.getName()).matches()) {
                                throw new IOException("Installed source retains unverified signing artifacts");
                            }
                            if (centralDirectoryRecord.getLocalFileHeaderOffset() != startOffsetInArchive) {
                                throw new IOException("Installed source has an unexplained gap or overlapping ZIP records");
                            }
                            LocalFileRecord record = LocalFileRecord.getRecord(dataSourceAsDataSource, centralDirectoryRecord, zipCentralDirectoryOffset);
                            startOffsetInArchive = record.getStartOffsetInArchive() + record.getSize();
                            if (startOffsetInArchive > zipCentralDirectoryOffset) {
                                throw new IOException("Installed source ZIP overlaps its directory");
                            }
                        }
                        if (startOffsetInArchive != zipCentralDirectoryOffset) {
                            throw new IOException("Installed source retains an unverified signing block or unexplained ZIP gap");
                        }
                        randomAccessFile.close();
                        return;
                    }
                }
                throw new IOException("Installed source has invalid ZIP directory bounds");
            } catch (Throwable th) {
                try {
                    randomAccessFile.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e2) {
            throw new IOException("Installed source signing container could not be classified safely", e2);
        }
    }
}
