package ovh.maddie480.everest.updatechecker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static ServerConfig serverConfig;

    public static void main(String[] args) throws InterruptedException {
        // read the config file.
        try (InputStream is = Files.newInputStream(Paths.get("update_checker_config.yaml"))) {
            Map<String, Object> config = YamlUtil.load(is);
            serverConfig = new ServerConfig(config);
        } catch (IOException e) {
            log.error("Could not load update_checker_config.yaml!", e);
            System.exit(1);
        }

        int updateRate = 30;
        if (args.length > 0) {
            try {
                updateRate = Integer.parseInt(args[0]);
                if (updateRate <= 0) {
                    log.error("Provided updateRate should be a positive number (number of minutes between two updates). Falling back to 30.");
                    updateRate = 30;
                }
            } catch (NumberFormatException e) {
                log.error("Provided updateRate should be a number (number of minutes between two updates). Falling back to 30.");
            }
        }

        while (true) {
            updateDatabase(true);

            log.info("Waiting for {} minute(s) before next update.", updateRate);
            Thread.sleep(updateRate * 60_000L);
        }
    }

    public static void updateDatabase(boolean full) {
        try {
            DatabaseUpdater.updateDatabaseYaml(full);
        } catch (Exception e) {
            log.error("Uncaught error while updating the database.", e);
            EventListener.handle(listener -> listener.uncaughtError(e));
        }
    }
}
