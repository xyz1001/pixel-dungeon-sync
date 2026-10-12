package io.github.xyz1001.pdsync.bridge;

import java.io.IOException;


final class BridgeFailure extends IOException {
    final String code;

    BridgeFailure(String str, String str2) {
        super(str2);
        this.code = str;
    }

    BridgeFailure(String str, String str2, Throwable th) {
        super(str2, th);
        this.code = str;
    }
}
