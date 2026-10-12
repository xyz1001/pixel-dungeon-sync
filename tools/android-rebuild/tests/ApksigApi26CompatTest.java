package com.android.apksig.internal.asn1;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public final class ApksigApi26CompatTest {
    public List<String> strings;
    public List<Asn1OpaqueObject> objects;
    public List<ByteBuffer> buffers;
    public List raw;
    public List<?> wildcard;
    public List<List<String>> nested;
    public Map<String, Integer> twoArguments;
    public String notAContainer;

    @Asn1Class(type = Asn1Type.SEQUENCE)
    public static final class Container {
        @Asn1Field(index = 0, type = Asn1Type.SET_OF)
        public List<Asn1OpaqueObject> items;
        public Container() {}
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        require(Android26ElementType.getElementType(field("strings")) == String.class, "String element");
        require(Android26ElementType.getElementType(field("objects")) == Asn1OpaqueObject.class, "Opaque element");
        require(Android26ElementType.getElementType(field("buffers")) == ByteBuffer.class, "ByteBuffer element");
        for (String name : Arrays.asList("raw", "wildcard", "nested", "twoArguments", "notAContainer")) {
            boolean rejected = false;
            try { Android26ElementType.getElementType(field(name)); }
            catch (Asn1DecodingException expected) { rejected = true; }
            require(rejected, "Unsupported generic shape accepted: " + name);
        }
        // The actual patched parser must take its SET_OF element lookup path.
        byte[] encoded = {0x30, 0x05, 0x31, 0x03, 0x02, 0x01, 0x07};
        Container parsed = Asn1BerParser.parse(ByteBuffer.wrap(encoded), Container.class);
        require(parsed.items.size() == 1, "Parser did not decode SET_OF");
        require(Arrays.equals(Asn1DerEncoder.encode(parsed), encoded), "ASN.1 round trip changed data");
        System.out.println("APKSIG_API26_COMPAT_OK: 8 generic-shape cases and actual SET_OF round trip");
    }

    private static Field field(String name) throws Exception {
        return ApksigApi26CompatTest.class.getField(name);
    }
}
