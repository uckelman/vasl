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
    public void testBoardDirNotNull() {
        logger.info("Board directory property name: " + Constants.BOARD_DIR);
        assertNotNull(Constants.BOARD_DIR);
    }
}