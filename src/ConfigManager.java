// Config Manager Class
// Loads and manages user configuration from ~/.shedrc

import java.io.File;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.awt.Color;

public class ConfigManager {
    private Map<String, String> config;
    private String configPath;

    // Default configuration values
    private static final String DEFAULT_COLOR_NORMAL = "#BC0E4C";
    private static final String DEFAULT_COLOR_INSERT = "#354F60";
    private static final String DEFAULT_COLOR_COMMAND = "#FFC501";
    private static final String DEFAULT_COLOR_VISUAL = "#2E8B57";
    private static final String DEFAULT_COLOR_REPLACE = "#8B4513";
    private static final String DEFAULT_FONT_FAMILY = "Hack";
    private static final int DEFAULT_FONT_SIZE = 16;
    private static final int DEFAULT_TAB_SIZE = 4;
    private static final boolean DEFAULT_LINE_NUMBERS = false;

    public ConfigManager() {
        this.config = new HashMap<>();
        this.configPath = System.getProperty("user.home") + "/.shedrc";

        // Try alternate config location
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            String altPath = System.getProperty("user.home") + "/.config/shed/shedrc";
            File altFile = new File(altPath);
            if (altFile.exists()) {
                this.configPath = altPath;
            }
        }

        loadDefaults();
        loadConfig();
    }

    // Load default configuration
    private void loadDefaults() {
        config.put("color.normal", DEFAULT_COLOR_NORMAL);
        config.put("color.insert", DEFAULT_COLOR_INSERT);
        config.put("color.command", DEFAULT_COLOR_COMMAND);
        config.put("color.visual", DEFAULT_COLOR_VISUAL);
        config.put("color.replace", DEFAULT_COLOR_REPLACE);
        config.put("font.family", DEFAULT_FONT_FAMILY);
        config.put("font.size", String.valueOf(DEFAULT_FONT_SIZE));
        config.put("tab.size", String.valueOf(DEFAULT_TAB_SIZE));
        config.put("line.numbers", String.valueOf(DEFAULT_LINE_NUMBERS));
    }

    // Load configuration from file
    private void loadConfig() {
        File configFile = new File(configPath);
        if (!configFile.exists()) {
            return; // Use defaults
        }

        try {
            BufferedReader reader = new BufferedReader(new FileReader(configFile));
            String line;

            while ((line = reader.readLine()) != null) {
                line = line.trim();

                // Skip comments and empty lines
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Parse key=value pairs
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String key = parts[0].trim();
                    String value = parts[1].trim();
                    config.put(key, value);
                }
            }

            reader.close();
        } catch (IOException e) {
            System.err.println("Error loading config: " + e.getMessage());
        }
    }

    // Get color setting
    public Color getColor(String mode) {
        String key = "color." + mode.toLowerCase();
        String colorHex = config.getOrDefault(key, DEFAULT_COLOR_NORMAL);
        try {
            return Color.decode(colorHex);
        } catch (NumberFormatException e) {
            return Color.decode(DEFAULT_COLOR_NORMAL);
        }
    }

    // Get normal mode color
    public Color getNormalColor() {
        return getColor("normal");
    }

    // Get insert mode color
    public Color getInsertColor() {
        return getColor("insert");
    }

    // Get command mode color
    public Color getCommandColor() {
        return getColor("command");
    }

    // Get visual mode color
    public Color getVisualColor() {
        return getColor("visual");
    }

    // Get replace mode color
    public Color getReplaceColor() {
        return getColor("replace");
    }

    // Get font family
    public String getFontFamily() {
        return config.getOrDefault("font.family", DEFAULT_FONT_FAMILY);
    }

    // Get font size
    public int getFontSize() {
        try {
            return Integer.parseInt(config.getOrDefault("font.size", String.valueOf(DEFAULT_FONT_SIZE)));
        } catch (NumberFormatException e) {
            return DEFAULT_FONT_SIZE;
        }
    }

    // Get tab size
    public int getTabSize() {
        try {
            return Integer.parseInt(config.getOrDefault("tab.size", String.valueOf(DEFAULT_TAB_SIZE)));
        } catch (NumberFormatException e) {
            return DEFAULT_TAB_SIZE;
        }
    }

    // Get line numbers preference
    public boolean getLineNumbers() {
        String value = config.getOrDefault("line.numbers", String.valueOf(DEFAULT_LINE_NUMBERS));
        return Boolean.parseBoolean(value);
    }

    // Get any config value by key
    public String get(String key) {
        return config.get(key);
    }

    // Get config value with default
    public String get(String key, String defaultValue) {
        return config.getOrDefault(key, defaultValue);
    }

    // Get config file path
    public String getConfigPath() {
        return configPath;
    }

    // Check if config file exists
    public boolean configExists() {
        return new File(configPath).exists();
    }
}
