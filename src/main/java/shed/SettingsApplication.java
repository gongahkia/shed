package shed;

import java.util.List;

/** Settings use cases. Calls and successful update callbacks are synchronous on the caller's
 * thread; the Swing composition calls these on the EDT. No subscriptions are retained. */
public interface SettingsApplication {
    record Setting(String key, String category, String type, String currentValue, String defaultValue,
                   String description, String allowedValues, String applyBehavior) { }
    List<Setting> search(String query);
    String update(String key, String value);
}
