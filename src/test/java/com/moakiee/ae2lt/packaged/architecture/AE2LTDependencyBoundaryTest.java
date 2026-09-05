package com.moakiee.ae2lt.packaged.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class AE2LTDependencyBoundaryTest {
    private static final Path MAIN_SOURCES = Path.of("src/main/java");
    private static final Pattern AE2LT_REFERENCE =
            Pattern.compile("com\\.moakiee\\.ae2lt\\.([A-Za-z0-9_]+)(?:\\.[A-Za-z0-9_]+)*");
    private static final Set<String> ALLOWED_ROOT_PACKAGES = Set.of("api", "packaged");
    private static final String WIRELESS_SUPPORT_CONTRACT =
            "com.moakiee.ae2lt.logic.wireless.support.";

    // Connector guards have no public extension point. Keep their exceptions local to the mixins.
    private static final Map<String, Set<String>> CONNECTOR_MIXIN_CONTRACTS = Map.of(
            "com/moakiee/ae2lt/packaged/mixin/WirelessConnectorItemMixin.java", Set.of(
                    "com.moakiee.ae2lt.item.OverloadedWirelessConnectorItem",
                    "com.moakiee.ae2lt.network.NetworkInit",
                    "com.moakiee.ae2lt.network.WirelessConnectorUsePacket"),
            "com/moakiee/ae2lt/packaged/mixin/WirelessConnectorPacketMixin.java", Set.of(
                    "com.moakiee.ae2lt.item.OverloadedWirelessConnectorItem",
                    "com.moakiee.ae2lt.logic.WirelessConnectorTargetHelper",
                    "com.moakiee.ae2lt.network.WirelessConnectorUsePacket"));

    @Test
    void mainSourcesOnlyUseAE2LTPublicApi() throws IOException {
        var violations = new ArrayList<String>();

        try (var sources = Files.walk(MAIN_SOURCES)) {
            for (var source : sources.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                var lines = Files.readAllLines(source);
                for (var lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                    var matcher = AE2LT_REFERENCE.matcher(lines.get(lineIndex));
                    while (matcher.find()) {
                        if (!ALLOWED_ROOT_PACKAGES.contains(matcher.group(1))
                                && !matcher.group().startsWith(WIRELESS_SUPPORT_CONTRACT)
                                && !CONNECTOR_MIXIN_CONTRACTS.getOrDefault(
                                        MAIN_SOURCES.relativize(source).toString().replace('\\', '/'), Set.of())
                                        .contains(matcher.group())) {
                            violations.add(source + ":" + (lineIndex + 1) + " -> " + matcher.group());
                        }
                    }
                }
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "Main sources must use AE2LT public APIs or the documented wireless support contract:\n"
                        + String.join("\n", violations));
    }

    @Test
    void packagedProviderImplementationIsOwnedByThisAddon() throws IOException {
        var providerRoot = MAIN_SOURCES.resolve(
                "com/moakiee/ae2lt/packaged/patternprovider");
        var expectedImplementationFiles = List.of(
                "AllowedOutputFilter.java",
                "InsertOnlyPatternProviderReturnInventory.java",
                "OverloadPatternSemantics.java",
                "PatternProviderPowerCost.java",
                "StablePatternProviderBlockEntity.java",
                "StablePatternProviderLogic.java",
                "UnlimitedPatternProviderReturnInventory.java");

        for (var file : expectedImplementationFiles) {
            assertTrue(Files.isRegularFile(providerRoot.resolve(file)), file);
        }

        var forbiddenPackage = "com.moakiee.thunderbolt.ae2.patternprovider";
        try (var sources = Files.walk(MAIN_SOURCES)) {
            var violations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(forbiddenPackage);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .toList();
            assertTrue(
                    violations.isEmpty(),
                    () -> "Provider implementation must remain in PP: " + violations);
        }

        assertTrue(Files.isRegularFile(Path.of(
                "src/main/resources/ae2ltpp.mixins.json")));
    }
}
