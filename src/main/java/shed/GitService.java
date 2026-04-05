package shed;

import java.io.File;
import java.util.Locale;

public class GitService {
    public interface Handler {
        String status(File root);
        String diff(File root, String args);
        String log(File root, String args);
        String branches(File root);
        String add(File root, String args);
        String stage(File root, String args);
        String restore(File root, String args);
        String unstage(File root, String args);
        String commit(File root, String args);
        String amend(File root, String args);
        String checkout(File root, String args);
        String switchBranch(File root, String args);
        String help();
    }

    public String handle(String argument, File root, Handler handler) {
        if (root == null) {
            return "Not inside a git repository";
        }
        String trimmed = argument == null ? "" : argument.trim();
        if (trimmed.isEmpty()) {
            return handler.status(root);
        }

        int split = trimmed.indexOf(' ');
        String subcommand = split < 0 ? trimmed : trimmed.substring(0, split).trim();
        String rest = split < 0 ? "" : trimmed.substring(split + 1).trim();
        subcommand = subcommand.toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "status":
            case "st":
                return handler.status(root);
            case "diff":
                return handler.diff(root, rest);
            case "log":
                return handler.log(root, rest);
            case "branch":
            case "branches":
                return handler.branches(root);
            case "add":
                return handler.add(root, rest);
            case "stage":
                return handler.stage(root, rest);
            case "restore":
                return handler.restore(root, rest);
            case "unstage":
                return handler.unstage(root, rest);
            case "commit":
                return handler.commit(root, rest);
            case "amend":
                return handler.amend(root, rest);
            case "checkout":
            case "co":
                return handler.checkout(root, rest);
            case "switch":
            case "sw":
                return handler.switchBranch(root, rest);
            case "help":
                return handler.help();
            default:
                return "Unknown git command: " + subcommand + " (use :git help)";
        }
    }
}
