package com.max480.everest.updatechecker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static ServerConfig serverConfig;

    public static void main(String[] args) throws InterruptedException {
        // read the config file.
        try (InputStream is = new FileInputStream("update_checker_config.yaml")) {
            Map<String, Object> config = new Yaml().load(is);
            serverConfig = new ServerConfig(config);
        } catch (IOException e) {
            log.error("Could not load update_checker_config.yaml!", e);
            System.exit(1);
        }

        int port = -1;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 0 || port > 65535) {
                    log.error("Provided port should be a positive number below 65535. Falling back to no HTTP server.");
                    port = -1;
                }
            } catch (NumberFormatException e) {
                log.error("Provided port should be a number. Falling back to no HTTP server.");
            }
        }

        int updateRate = 30;
        if (args.length > 1) {
            try {
                updateRate = Integer.parseInt(args[1]);
                if (updateRate <= 0) {
                    log.error("Provided updateRate should be a positive number (number of minutes between two updates). Falling back to 30.");
                    updateRate = 30;
                }
            } catch (NumberFormatException e) {
                log.error("Provided updateRate should be a number (number of minutes between two updates). Falling back to 30.");
            }
        }

        if (port > 0) {
            try {
                new HttpServer(port).start();
                log.info("Http server now listening to port {}", port);
            } catch (IOException e) {
                log.error("Error while starting server", e);
            }
        }

        while (true) {
            try {
                new DatabaseUpdater().updateDatabaseYaml();
            } catch (Exception e) {
                log.error("Uncaught error while updating the database.", e);
            }

            log.info("Waiting for {} minute(s) before next update.", updateRate);
            Thread.sleep(updateRate * 60_000);
        }
    }
}
