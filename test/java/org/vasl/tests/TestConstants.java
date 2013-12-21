package org.vasl.tests;

import VASL.Constants;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

/**
 * Tests for the {@code TestConstants}
 */
public class TestConstants {

    private static final Logger logger = LoggerFactory.getLogger(TestConstants.class);

    @Test
    public void testVaslVersionLoading() {
        logger.info("VASL Version: " + Constants.VASL_VERSION);
        assertNotNull(Constants.VASL_VERSION);
        assertFalse(Constants.VASL_VERSION.startsWith("{"));
    }
}