package com.moakiee.ae2lt.packaged.patternprovider;

import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import org.junit.jupiter.api.Test;

/**
 * JUnit regression test requiring a full JDK.
 * Compiles unchanged production members extracted with the JDK parser, against
 * small boundary fakes, without Minecraft bootstrap or Mixin.
 * This exercises the actual ticker and connection-cache
 * control flow, not a second implementation. It is not an in-game integration
 * test: network injection and world access are controlled test doubles.
 */
public final class StablePatternProviderLogicRegressionTest {
    @Test
    void productionBehaviorAndOriginalBugMutations() throws Exception {
        Path root = Path.of("");
        String source = Files.readString(root.resolve(
                "src/main/java/com/moakiee/ae2lt/packaged/patternprovider/StablePatternProviderLogic.java"));
        String members = extractMembers(source);
        run(members);
        System.out.println("PASS: production ticker and connection-cache behavior (12 scenarios)");

        // Prove the suite detects both original bugs without editing production.
        String noSave = members.replace("saveChanges();", "");
        expectRegression(noSave, "successful work must save");
        String requiresBe = members.replace("valid.add(connection);",
                "if (targetLevel.getBlockEntity(connection.pos()) == null) continue; valid.add(connection);");
        expectRegression(requiresBe, "loaded block-only candidate must survive");
        System.out.println("PASS: both original-bug mutations rejected");
    }

    @Test
    void hostValidationPreservesOnlyRecognizedBlockOnlyTargets() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/moakiee/ae2lt/packaged/patternprovider/StablePatternProviderBlockEntity.java"));
        String validator = extractMembers(source, Set.of(), Set.of("validateConnection"), Set.of());
        String spiritFire = Files.readString(Path.of(
                "src/main/java/com/moakiee/ae2lt/packaged/logic/multiblock/occultism/OccultismSpiritFireAdapter.java"));
        String recognition = extractMembers(spiritFire, Set.of(), Set.of("recognizesMain"), Set.of());
        run(validator, VALIDATION_HARNESS.replace("/* SPIRIT_FIRE_RECOGNITION */", recognition));

        // Wiring contracts supplement behavior: all server mutations use the same validator.
        String add = extractMembers(source, Set.of(), Set.of("addOrUpdateConnection"), Set.of());
        org.junit.jupiter.api.Assertions.assertTrue(add.indexOf("validateConnection(")
                < add.indexOf("connections.set("));
        org.junit.jupiter.api.Assertions.assertTrue(add.contains("Status.VALID"));
        String prune = extractMembers(source, Set.of(), Set.of("pruneInvalidConnections"), Set.of());
        org.junit.jupiter.api.Assertions.assertTrue(prune.contains("validateConnection("));
        org.junit.jupiter.api.Assertions.assertTrue(prune.contains("Status.REMOVE"));
        org.junit.jupiter.api.Assertions.assertFalse(source.contains("WirelessConnectionValidator.validate("));
        String cleanup = extractMembers(source, Set.of(), Set.of("tickWirelessConnectionCleanup"), Set.of());
        org.junit.jupiter.api.Assertions.assertTrue(cleanup.contains("pruneInvalidConnections("));
    }

    @Test
    void connectorCompatibilityIsRegisteredAndScopedToPackagedHosts() throws Exception {
        String base = "src/main/java/com/moakiee/ae2lt/packaged/mixin/";
        String item = Files.readString(Path.of(base + "WirelessConnectorItemMixin.java"));
        String packet = Files.readString(Path.of(base + "WirelessConnectorPacketMixin.java"));
        String config = Files.readString(Path.of("src/main/resources/ae2ltpp.mixins.json"));
        var mixinConfig = com.google.gson.JsonParser.parseString(config).getAsJsonObject();
        var itemMixin = new com.google.gson.JsonPrimitive("WirelessConnectorItemMixin");
        var packetMixin = new com.google.gson.JsonPrimitive("WirelessConnectorPacketMixin");
        var commonMixins = mixinConfig.getAsJsonArray("mixins");
        var clientMixins = mixinConfig.getAsJsonArray("client");
        org.junit.jupiter.api.Assertions.assertTrue(clientMixins.contains(itemMixin),
                "Screen-referencing item mixin must load only on the client");
        org.junit.jupiter.api.Assertions.assertFalse(commonMixins.contains(itemMixin),
                "Item mixin must not be transformed on a dedicated server");
        org.junit.jupiter.api.Assertions.assertTrue(commonMixins.contains(packetMixin),
                "Packet validation must remain available on a dedicated server");
        org.junit.jupiter.api.Assertions.assertFalse(clientMixins.contains(packetMixin));
        if (mixinConfig.has("server")) {
            org.junit.jupiter.api.Assertions.assertFalse(
                    mixinConfig.getAsJsonArray("server").contains(itemMixin));
        }
        org.junit.jupiter.api.Assertions.assertFalse(item.contains("getSelectedProvider("));
        org.junit.jupiter.api.Assertions.assertTrue(item.contains("WirelessConnectorBlockOnlyTarget.shouldSubmit(providerSelected,"));
        org.junit.jupiter.api.Assertions.assertTrue(packet.contains("method = \"handleOnServer\""));
        org.junit.jupiter.api.Assertions.assertTrue(packet.contains("method = \"handleProviderConnection\""));
        org.junit.jupiter.api.Assertions.assertTrue(packet.contains("MultiblockAdapterRegistry.find(level, pos(), null) == null"));
        org.junit.jupiter.api.Assertions.assertTrue(packet.contains("return WirelessConnectorTargetHelper.collectTargets(level, pos, contiguous);"));
        org.junit.jupiter.api.Assertions.assertEquals(2,
                packet.split("instanceof StablePatternProviderBlockEntity provider", -1).length - 1);
    }

    private static String extractMembers(String source) throws Exception {
        return extractMembers(source,
                Set.of("GRID_TICK_MIN", "GRID_TICK_MAX", "VALIDATE_INTERVAL",
                        "validConnectionsCache", "validConnectionsCacheTick", "connectionsDirty"),
                Set.of("getValidConnections", "invalidateConnections",
                        "hasCombinedTickWork", "hasAnyTickWork", "hasAutoReturnWork"),
                Set.of("Ticker"));
    }

    private static String extractMembers(String source, Set<String> fields,
            Set<String> methods, Set<String> classes) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("A full JDK is required");
        }
        var input = new SimpleJavaFileObject(URI.create("string:///StablePatternProviderLogic.java"),
                javax.tools.JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (var files = compiler.getStandardFileManager(null, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, null,
                    List.of("-proc:none"), null, List.of(input));
            var unit = task.parse().iterator().next();
            var positions = Trees.instance(task).getSourcePositions();
            var type = (ClassTree) unit.getTypeDecls().get(0);
            StringBuilder selected = new StringBuilder();
            int count = 0;
            for (var member : type.getMembers()) {
                boolean include = member instanceof VariableTree v && fields.contains(v.getName().toString())
                        || member instanceof MethodTree m && methods.contains(m.getName().toString())
                        || member instanceof ClassTree c && classes.contains(c.getSimpleName().toString());
                if (include) {
                    selected.append(source, (int) positions.getStartPosition(unit, member),
                            (int) positions.getEndPosition(unit, member)).append('\n');
                    count++;
                }
            }
            if (count != fields.size() + methods.size() + classes.size()) {
                throw new AssertionError("Production members changed; update the harness explicitly");
            }
            return selected.toString();
        }
    }

    private static void expectRegression(String members, String expectedMessage) throws Exception {
        try {
            run(members);
        } catch (AssertionError failure) {
            if (expectedMessage.equals(failure.getMessage())) {
                return;
            }
            throw failure;
        }
        throw new AssertionError("Suite failed to detect regression: " + expectedMessage);
    }

    private static void run(String members) throws Exception {
        run(members, HARNESS);
    }

    private static void run(String members, String harness) throws Exception {
        Path temp = Files.createTempDirectory("stable-provider-regression-");
        try {
            Path file = temp.resolve("ProviderHarness.java");
            Files.writeString(file, "import java.util.*;\npublic class ProviderHarness {\n"
                    + members + harness + "\n}");
            int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-proc:none", "-d", temp.toString(), file.toString());
            if (result != 0) {
                throw new AssertionError("Harness compilation failed: " + result);
            }
            try (var loader = new URLClassLoader(new java.net.URL[]{temp.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass("ProviderHarness").getMethod("verify").invoke(null);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    if (e.getCause() instanceof AssertionError failure) {
                        throw failure;
                    }
                    throw new AssertionError("Harness execution failed", e.getCause());
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    private static final String VALIDATION_HARNESS = """
        record BlockPos(int x) {}
        record WirelessConnectionRef(String dimension, BlockPos pos) {}
        static class WirelessConnectionValidator { enum Status { VALID, UNLOADED, REMOVE } }
        static class WirelessConnectionRange {
            static boolean isInRange(String from, BlockPos origin, String to, BlockPos pos, int max) {
                return from.equals(to) && Math.abs(pos.x() - origin.x()) <= max;
            }
        }
        static class State { boolean air; boolean isAir() { return air; } }
        static class BlockEntity {}
        interface Adapter { boolean recognizesMain(ServerLevel level, BlockPos pos, BlockEntity be); }
        static class SpiritFireAdapter implements Adapter {
            static final String SPIRIT_FIRE_BLOCK = "occultism:spirit_fire";
            boolean isOccultismLoaded() { return true; }
            String blockId(State state) { return state.air ? "minecraft:air" : SPIRIT_FIRE_BLOCK; }
            /* SPIRIT_FIRE_RECOGNITION */
        }
        static class ServerLevel {
            boolean exists = true, loaded = true, recognized = true;
            Object be;
            State state = new State();
            int reads, adapterReads;
            String dimension() { return "home"; }
            ServerLevel getServer() { return this; }
            ServerLevel getLevel(String dimension) { return exists ? this : null; }
            boolean isLoaded(BlockPos pos) { return loaded; }
            State getBlockState(BlockPos pos) { read(); return state; }
            Object getBlockEntity(BlockPos pos) { read(); return be; }
            void read() {
                check(loaded && exists, "no world reads before loaded check");
                reads++;
            }
        }
        static class MultiblockAdapterRegistry {
            static Object find(ServerLevel level, BlockPos pos, Object be) {
                level.read(); level.adapterReads++;
                var adapter = new SpiritFireAdapter();
                return level.recognized && adapter.recognizesMain(level, pos, null) ? adapter : null;
            }
        }
        static void check(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
        public static void verify() {
            var level = new ServerLevel();
            var origin = new BlockPos(0);
            var fire = new WirelessConnectionRef("home", new BlockPos(4));
            for (int i = 0; i < 1000; i++) {
                check(validateConnection(level, origin, fire, 32) == WirelessConnectionValidator.Status.VALID,
                        "recognized Spirit Fire survives repeated validation without BE");
            }
            level.recognized = false;
            check(validateConnection(level, origin, fire, 32) == WirelessConnectionValidator.Status.REMOVE,
                    "unrecognized non-BE block rejected");
            level.state.air = true;
            check(validateConnection(level, origin, fire, 32) == WirelessConnectionValidator.Status.REMOVE,
                    "non-adapter air rejected after replacement");
            level.state.air = false; level.be = new Object();
            int adapters = level.adapterReads;
            check(validateConnection(level, origin, fire, 32) == WirelessConnectionValidator.Status.VALID
                    && level.adapterReads == adapters, "ordinary BE target retains existing semantics");
            level.loaded = false;
            int reads = level.reads;
            check(validateConnection(level, origin, fire, 32) == WirelessConnectionValidator.Status.UNLOADED
                    && level.reads == reads, "unloaded connection retained without reads");
            level.exists = false;
            check(validateConnection(level, origin, fire, 32) == WirelessConnectionValidator.Status.REMOVE
                    && level.reads == reads, "missing dimension removed without reads");
            level.exists = true; level.loaded = true; level.be = null; level.recognized = true;
            check(validateConnection(level, origin, fire, 2) == WirelessConnectionValidator.Status.REMOVE
                    && level.reads == reads, "adapter cannot bypass range");
            check(validateConnection(level, origin, new WirelessConnectionRef("other", fire.pos()), 32)
                    == WirelessConnectionValidator.Status.REMOVE && level.reads == reads,
                    "adapter cannot bypass dimension");
            check(validateConnection(level, origin, fire, 32) == WirelessConnectionValidator.Status.VALID,
                    "reloaded recognized target valid again");
        }
        """;

    private static final String HARNESS = """
        final StablePatternProviderBlockEntity providerHost = new StablePatternProviderBlockEntity();
        final GridNode gridNode = new GridNode();
        final ReturnInventory returnInventory = new ReturnInventory();
        final Accessor parent = new Accessor();
        final List<String> events = new ArrayList<>();
        long savedAmount = -1;
        int saves;
        boolean failAutoReturn;
        Accessor accessor() { return parent; }
        void saveChanges() {
            events.add("save");
            saves++;
            savedAmount = returnInventory.amount;
        }
        void tickAutoReturn() {
            events.add("auto");
            if (failAutoReturn) throw new IllegalStateException("auto failure");
        }
        TickRateModulation tick() { return new Ticker().tickingRequest(null, 1); }
        class Accessor {
            boolean workRemaining;
            long accepted;
            boolean ae2ltpp$hasWorkToDo() { return workRemaining; }
            boolean ae2ltpp$doWork() {
                events.add("work");
                // Boundary fake: like injectIntoNetwork, mutate without a listener.
                long transferred = Math.min(accepted, returnInventory.amount);
                returnInventory.amount -= transferred;
                return transferred > 0;
            }
        }
        static class ReturnInventory {
            long amount;
            boolean isEmpty() { return amount == 0; }
        }
        static class GridNode { boolean active = true; boolean isActive() { return active; } }
        interface IGridNode {}
        interface IGridTickable {
            TickingRequest getTickingRequest(IGridNode node);
            TickRateModulation tickingRequest(IGridNode node, int elapsed);
        }
        enum TickRateModulation { SLEEP, SLOWER, URGENT }
        record TickingRequest(int min, int max, boolean sleeping, boolean alertable) {}
        static class StablePatternProviderBlockEntity {
            enum ReturnMode { OFF, AUTO }
            record WirelessConnection(String dimension, int pos) {}
            final List<WirelessConnection> connections = new ArrayList<>();
            ReturnMode mode = ReturnMode.OFF;
            ReturnMode getReturnMode() { return mode; }
            List<WirelessConnection> getConnections() { return connections; }
            int getBlockPos() { return 0; }
        }
        static class WirelessPatternProviderPolicy { static int maxDistance() { return 32; } }
        static class WirelessConnectionRange {
            static int calls;
            // Controlled range boundary, not a test of AE2LT's distance algorithm.
            static boolean isInRange(String from, int origin, String to, int pos, int max) {
                calls++;
                check(from.equals("home") && origin == 0 && max == 32, "range arguments");
                return pos != 99;
            }
        }
        static class Server {
            final Map<String, ServerLevel> levels = new HashMap<>();
            ServerLevel getLevel(String dimension) { return levels.get(dimension); }
        }
        static class ServerLevel {
            final Server server;
            final Set<Integer> loaded = new HashSet<>(List.of(1, 2, 99));
            int blockEntityReads;
            ServerLevel(Server server) { this.server = server; }
            String dimension() { return "home"; }
            Server getServer() { return server; }
            boolean isLoaded(int pos) { return loaded.contains(pos); }
            Object getBlockEntity(int pos) {
                blockEntityReads++;
                check(isLoaded(pos), "must not read unloaded target");
                return pos == 2 ? new Object() : null;
            }
        }
        static void check(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
        public static void verify() {
            // Final drain must save even though the ticker immediately sleeps.
            var p = new ProviderHarness();
            p.returnInventory.amount = 8; p.parent.accepted = 8;
            check(p.tick() == TickRateModulation.SLEEP, "final drain sleeps");
            check(p.saves == 1 && p.savedAmount == 0, "successful work must save");
            check(p.events.equals(List.of("work", "save", "auto")), "save before auto return");

            p = new ProviderHarness();
            p.returnInventory.amount = 8; p.parent.accepted = 3;
            check(p.tick() == TickRateModulation.SLOWER, "partial drain slows");
            check(p.savedAmount == 5 && p.saves == 1, "partial residual persisted");

            p = new ProviderHarness(); p.returnInventory.amount = 8;
            check(p.tick() == TickRateModulation.SLOWER && p.saves == 0, "blocked return not dirty");
            p = new ProviderHarness();
            check(p.tick() == TickRateModulation.SLEEP && p.saves == 0, "idle not dirty");
            p = new ProviderHarness(); p.gridNode.active = false; p.returnInventory.amount = 8;
            check(p.tick() == TickRateModulation.SLEEP && p.events.isEmpty(), "inactive has no side effects");

            p = new ProviderHarness(); p.providerHost.mode = StablePatternProviderBlockEntity.ReturnMode.AUTO;
            p.returnInventory.amount = 1; p.parent.accepted = 1; p.parent.workRemaining = true;
            check(p.tick() == TickRateModulation.SLOWER && p.saves == 1, "auto cadence wins");
            p.providerHost.mode = StablePatternProviderBlockEntity.ReturnMode.OFF;
            p.returnInventory.amount = 1;
            check(p.tick() == TickRateModulation.URGENT, "successful remaining parent work urgent");
            check(p.tick() == TickRateModulation.SLOWER, "blocked parent work slower");

            p = new ProviderHarness(); p.returnInventory.amount = 1; p.parent.accepted = 1;
            p.failAutoReturn = true;
            try { p.tick(); throw new AssertionError("expected auto failure"); }
            catch (IllegalStateException expected) { check(p.savedAmount == 0, "save survives auto exception"); }

            p = new ProviderHarness();
            check(p.new Ticker().getTickingRequest(null).sleeping(), "idle initial request");
            p.returnInventory.amount = 1;
            var request = p.new Ticker().getTickingRequest(null);
            check(!request.sleeping() && request.min() == 1 && request.max() == 20
                    && request.alertable(), "return inventory wakes initial request");

            var server = new Server(); var level = new ServerLevel(server);
            server.levels.put("home", level);
            var blockOnly = new StablePatternProviderBlockEntity.WirelessConnection("home", 1);
            var machine = new StablePatternProviderBlockEntity.WirelessConnection("home", 2);
            p.providerHost.connections.addAll(List.of(blockOnly, machine,
                    new StablePatternProviderBlockEntity.WirelessConnection("home", 3),
                    new StablePatternProviderBlockEntity.WirelessConnection("missing", 1),
                    new StablePatternProviderBlockEntity.WirelessConnection("home", 99)));
            var valid = p.getValidConnections(level, 100);
            check(valid.contains(blockOnly), "loaded block-only candidate must survive");
            check(valid.equals(List.of(blockOnly, machine)), "range, dimension and loaded guards");
            check(level.blockEntityReads == 0, "candidate filter leaves BE checks to adapters");
            try { valid.clear(); throw new AssertionError("cache must be immutable"); }
            catch (UnsupportedOperationException expected) {}

            int calls = WirelessConnectionRange.calls;
            check(p.getValidConnections(level, 119) == valid && WirelessConnectionRange.calls == calls,
                    "cache remains valid before interval");
            level.loaded.remove(1);
            check(p.getValidConnections(level, 120).equals(List.of(machine)), "interval revalidates unloaded target");
            p.providerHost.connections.clear();
            p.invalidateConnections();
            check(p.getValidConnections(level, 121).isEmpty(), "dirty cache refreshes immediately");
        }
        """;
}
