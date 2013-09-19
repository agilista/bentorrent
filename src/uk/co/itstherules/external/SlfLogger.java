package uk.co.itstherules.external;

import java.text.MessageFormat;

public final class SlfLogger {
    public void info(String s, Object... objects){
        System.out.println(MessageFormat.format(s, objects));
    }
    public void trace(String s, Object... objects){ info(s,objects); }
    public void debug(String s, Object... objects){ info(s,objects); }
    public void error(String s, Object... objects){ info(s,objects); }
    public void warn(String s, Object... objects){ info(s,objects); }
}
