package VASL;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Container for storing magical literal values that are valid across the entire module.
 */
public class Constants {

    public static final String VASL_VERSION_NAME = "vasl.version";

    public static final String VASL_VERSION;

    static {
        InputStream stream = Constants.class.getClassLoader().getResourceAsStream("version.properties");
        if (stream == null) {
            throw new RuntimeException("Could not read version.properties from context root.");
        }
        Properties properties = new Properties();
        try {
            properties.load(stream);
        } catch (IOException e) {
            throw new RuntimeException("Exception loading version.properties, wrong format? (" + e.getMessage() + ")");
        }

        VASL_VERSION = properties.getProperty(VASL_VERSION_NAME);

        if (VASL_VERSION == null || VASL_VERSION.length() == 0) {
            throw new RuntimeException("Invalid VASL version in version.properties");
        }
    }
}