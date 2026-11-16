package imcbot;

import imcbot.cmi.CmiClient;
import java.nio.file.Path;

/** Usage: probe [seconds] | run */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        Config config = Config.load(Path.of("bot.properties"));
        CmiClient client = new CmiClient(config.url(), config.username(), config.password(),
                config.requestsPerSecond());
        String mode = args.length > 0 ? args[0] : "probe";
        if (mode.equals("probe")) {
            Probe.run(client, args.length > 1 ? Long.parseLong(args[1]) : 10);
        } else if (mode.equals("run")) {
            new Bot(client, config).run();
        } else {
            System.out.println("Usage: probe [seconds] | run");
        }
    }
}
