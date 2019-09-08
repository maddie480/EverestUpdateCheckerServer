package com.max480.everest.updatechecker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        int updateRate = 30;
        if(args.length != 0) {
            try {
                updateRate = Integer.parseInt(args[0]);
                if(updateRate <= 0) {
                    log.error("Provided argument should be a positive number (number of minutes between two updates). Falling back to 30.");
                    updateRate = 30;
                }
            } catch(NumberFormatException e) {
                log.error("Provided argument should be a number (number of minutes between two updates). Falling back to 30.");
            }
        }

        while(true) {
            try {
                new DatabaseUpdater().updateDatabaseYaml();
            } catch(Exception e) {
                log.error("Uncaught error while updating the database.", e);
            }

            log.info("Waiting for {} minute(s) before next update.", updateRate);
            Thread.sleep(updateRate * 60_000);
        }
    }
}
