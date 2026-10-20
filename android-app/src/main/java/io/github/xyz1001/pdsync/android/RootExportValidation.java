package io.github.xyz1001.pdsync.android;

import pdsync.i18n.AndroidMessages;

import java.io.*;
import java.util.zip.ZipFile;

/** Reject truncated/trailing ZIP framing before extracting any root-provided data. */
final class RootExportValidation {
    private RootExportValidation() {}
    static void requireCompleteArchive(File archive,int expectedLength,int expectedFiles)throws IOException {
        long size=archive.length();
        if(size!=expectedLength || size<22 || size>SaveArchiveCodec.MAX_BYTES || expectedFiles<0 || expectedFiles>4096)
            throw new IOException(AndroidMessages.ROOT_EXPORT_VALIDATION_01.text());
        // Our fixed runner emits archives without comments, multi-disk fields or ZIP64.
        try(RandomAccessFile file=new RandomAccessFile(archive,"r")) {
            file.seek(size-22);byte[] end=new byte[22];file.readFully(end);
            if(u32(end,0)!=0x06054b50L || u16(end,4)!=0 || u16(end,6)!=0 || u16(end,20)!=0
                    || u16(end,8)!=expectedFiles || u16(end,10)!=expectedFiles
                    || u32(end,12)+u32(end,16)!=size-22)
                throw new IOException(AndroidMessages.ROOT_EXPORT_VALIDATION_02.text());
            if(expectedFiles==0 && size!=22)throw new IOException(AndroidMessages.ROOT_EXPORT_VALIDATION_03.text());
            if(expectedFiles>0) {
                file.seek(0);if(Integer.reverseBytes(file.readInt())!=0x04034b50)throw new IOException(AndroidMessages.ROOT_EXPORT_VALIDATION_04.text());
                file.seek(u32(end,16));if(Integer.reverseBytes(file.readInt())!=0x02014b50)throw new IOException(AndroidMessages.ROOT_EXPORT_VALIDATION_05.text());
            }
        }
        try(ZipFile zip=new ZipFile(archive)){if(zip.size()!=expectedFiles)throw new IOException(AndroidMessages.ROOT_EXPORT_VALIDATION_06.text());}
    }
    private static int u16(byte[] bytes,int start){return (bytes[start]&255)|((bytes[start+1]&255)<<8);}
    private static long u32(byte[] bytes,int start){return u16(bytes,start)|((long)u16(bytes,start+2)<<16);}
}
