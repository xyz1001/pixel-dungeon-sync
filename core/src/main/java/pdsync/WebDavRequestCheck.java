package pdsync;

import java.io.IOException;

/** Runs immediately before each DAV wire exchange, including automatic HTTP follow-ups. */
@FunctionalInterface
public interface WebDavRequestCheck {
    void check() throws IOException;
}
