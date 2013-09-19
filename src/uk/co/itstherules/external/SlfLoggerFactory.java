package uk.co.itstherules.external;

public final class SlfLoggerFactory {
    public static SlfLogger getLogger(Class<?> aClass) {
        return new SlfLogger();
    }
}
