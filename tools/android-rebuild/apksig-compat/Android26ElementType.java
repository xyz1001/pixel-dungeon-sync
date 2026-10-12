/*
 * Copyright 2026 PD Sync contributors
 * Licensed under the Apache License, Version 2.0.
 * See android-app/assets/licenses/apksig-LICENSE.txt.
 */
package com.android.apksig.internal.asn1;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;

/** API-26 implementation of apksig's concrete ASN.1 collection element lookup. */
public final class Android26ElementType {
    private Android26ElementType() {}

    public static Class<?> getElementType(Field field) throws Asn1DecodingException {
        Type generic = field.getGenericType();
        if (!(generic instanceof ParameterizedType)) {
            throw new Asn1DecodingException("Not a parameterized container type: " + generic);
        }
        Type[] arguments = ((ParameterizedType) generic).getActualTypeArguments();
        if (arguments.length != 1 || !(arguments[0] instanceof Class<?>)) {
            throw new Asn1DecodingException("ASN.1 container must have one concrete element class: " + generic);
        }
        return (Class<?>) arguments[0];
    }
}
