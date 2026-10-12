package io.github.xyz1001.pdsync.root;

import io.github.xyz1001.pdsync.bridge.SaveArchive;
import java.io.IOException;
import java.io.InputStream;

/** Bounded ZIP envelope checks, in addition to the unchanged shared ZipFile/CRC parser. */
final class RootArchiveInput extends InputStream {
    private final InputStream input;
    private final int declared;
    private final byte[] tail=new byte[65557], first=new byte[4];
    private long count;
    RootArchiveInput(InputStream input,int declared)throws IOException {
        if(declared<22||declared>SaveArchive.MAX_BYTES)throw new IOException("Invalid archive length");
        this.input=input;this.declared=declared;
    }
    @Override public int read()throws IOException {byte[] one=new byte[1];return read(one,0,1)<0?-1:one[0]&255;}
    @Override public int read(byte[] bytes,int offset,int length)throws IOException {
        int n=input.read(bytes,offset,length);if(n==0&&length!=0)throw new IOException("Empty archive read");
        for(int i=0;i<n;i++) {if(count>=declared)throw new IOException("Archive exceeds declaration");byte value=bytes[offset+i];if(count<4)first[(int)count]=value;tail[(int)(count%tail.length)]=value;count++;}return n;
    }
    @Override public void close(){/* The protocol owns stdin; finish must inspect its EOF. */}
    void verify(int expectedEntries)throws IOException {
        if(count!=declared||expectedEntries<1||expectedEntries>SaveArchive.MAX_FILES)throw new IOException("Invalid archive count");
        if((first[0]&255)!=0x50||(first[1]&255)!=0x4b||(first[2]&255)!=3||(first[3]&255)!=4)throw new IOException("Missing local ZIP header");
        long end=-1;
        for(long at=count-22;at>=Math.max(0,count-tail.length);at--) {
            if(u32(at)==0x06054b50L&&at+22+u16(at+20)==count){end=at;break;}
        }
        if(end<0||u16(end+4)!=0||u16(end+6)!=0||u16(end+8)!=expectedEntries||u16(end+10)!=expectedEntries
                ||u32(end+12)==0xffffffffL||u32(end+16)==0xffffffffL||u32(end+12)+u32(end+16)!=end)
            throw new IOException("Invalid ZIP central framing");
    }
    private int u16(long at)throws IOException{return b(at)|(b(at+1)<<8);}
    private long u32(long at)throws IOException{return (long)u16(at)|((long)u16(at+2)<<16);}
    private int b(long at)throws IOException{if(at<Math.max(0,count-tail.length)||at>=count)throw new IOException("ZIP bounds");return tail[(int)(at%tail.length)]&255;}
}
