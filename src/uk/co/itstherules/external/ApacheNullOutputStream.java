package uk.co.itstherules.external;

import java.io.IOException;
import java.io.OutputStream;

public final class ApacheNullOutputStream extends OutputStream {

    public void write(byte[] b, int off, int len) {  }
    public void write(byte[] b) throws IOException {  }
    public void write(int b) {  }

}
