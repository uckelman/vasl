package org.vasl.tests;

import VASL.Constants;
import VASSAL.Info;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void testBoardDirectory() {
        Constants constants = new Constants();
        logger.info(Info.getHomeDir().getAbsolutePath());
        File boardDirectory = constants.getBoardFolder();
        assertTrue(boardDirectory.exists());
        assertTrue(boardDirectory.getParent().equals(Info.getHomeDir().getAbsolutePath()));
    }
}