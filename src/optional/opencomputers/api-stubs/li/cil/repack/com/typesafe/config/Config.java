package li.cil.repack.com.typesafe.config;

/**
 * Compile-time stand-in for OpenComputers' relocated Typesafe Config type.
 * <br>
 * OC ships this class inside its own jar (relocated to {@code li.cil.repack}), so it is not available as a
 * Maven dependency. The OC API module only uses it as the declared type of {@link li.cil.oc.api.API#config},
 * and this stub is never packaged into the mod jar.
 */
public interface Config {
}
