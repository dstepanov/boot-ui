package io.github.jdubois.bootui.micronaut.security;

/**
 * Whether {@code micronaut-security} is on the application's classpath.
 *
 * <p>The Security Logs panel needs this in a place that is safe to call when the library is <em>absent</em>,
 * so the check is a class-name probe rather than a reference to a security type: a controller that imported
 * one directly could not be loaded at all without the dependency. Resolved once, since a classpath does not
 * change while the JVM runs.
 */
public final class MicronautSecurityPresence {

    private static final String LOGIN_SUCCESSFUL_EVENT = "io.micronaut.security.event.LoginSuccessfulEvent";

    private static final boolean PRESENT = probe();

    private MicronautSecurityPresence() {}

    public static boolean available() {
        return PRESENT;
    }

    private static boolean probe() {
        try {
            Class.forName(LOGIN_SUCCESSFUL_EVENT, false, MicronautSecurityPresence.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
