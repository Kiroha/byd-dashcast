package com.byd.dashcast.update;

/** Pure semantic/build version comparison used by the OTA release selector. */
final class OtaVersionPolicy {
    private OtaVersionPolicy() {}

    static boolean isNewer(String latest, String currentName, int currentCode) {
        int latestBuild = extractBuild(latest);
        int[] latestParts = parseVersion(stripSuffix(latest));
        int[] currentParts = parseVersion(stripSuffix(currentName));
        for (int index = 0; index < Math.max(latestParts.length, currentParts.length); index++) {
            int latestPart = index < latestParts.length ? latestParts[index] : 0;
            int currentPart = index < currentParts.length ? currentParts[index] : 0;
            if (latestPart != currentPart) return latestPart > currentPart;
        }
        return latestBuild > 0 && latestBuild > currentCode;
    }

    private static int extractBuild(String tag) {
        int dash = tag.indexOf('-');
        if (dash < 0 || dash + 1 >= tag.length()) return -1;
        String suffix = tag.substring(dash + 1);
        if (suffix.startsWith("build")) suffix = suffix.substring(5);
        else if (suffix.startsWith("b") && suffix.length() > 1
                && Character.isDigit(suffix.charAt(1))) suffix = suffix.substring(1);
        try { return Integer.parseInt(suffix); }
        catch (NumberFormatException ignored) { return -1; }
    }

    private static String stripSuffix(String version) {
        int dash = version.indexOf('-');
        return dash < 0 ? version : version.substring(0, dash);
    }

    private static int[] parseVersion(String version) {
        String[] parts = version.split("\\.");
        int[] numbers = new int[parts.length];
        for (int index = 0; index < parts.length; index++) {
            try { numbers[index] = Integer.parseInt(parts[index]); }
            catch (NumberFormatException ignored) { numbers[index] = 0; }
        }
        return numbers;
    }
}