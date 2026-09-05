package com.moakiee.ae2lt.packaged.logic;

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
import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;

import org.junit.jupiter.api.Test;

/** Executes unchanged production lifecycle methods with controlled world boundaries. */
public class PackagedRitualLifecycleRegressionTest {
    private static final Path BASE = Path.of("src/main/java/com/moakiee/ae2lt/packaged/logic");

    @Test
    void lifecycleBehavior() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        String ritual = Files.readString(BASE.resolve("multiblock/occultism/OccultismRitualAdapter.java"));
        String provider = Files.readString(BASE.resolve("PackagedPatternProviderLogic.java"));
        String methods = extract(ritual, Set.of("tickPending", "supportsPatternIndependentHarvest"), -1)
                + extract(ritual, Set.of("canDispatch"), 4)
                + extract(ritual, Set.of("extractOutputs"), 5);
        String polling = extract(provider,
                Set.of("runAutoReturnTick", "autoReturnNormal", "autoReturnWireless"), -1);
        run(HARNESS.replace("/* RITUAL */", methods).replace("/* PROVIDER */", polling));

        // Verify the harness rejects the two original lifecycle regressions.
        expectFailure(HARNESS.replace("/* RITUAL */", methods.replace(
                "tickPending(level, mainPos, scope);", ""))
                .replace("/* PROVIDER */", polling));
        expectFailure(HARNESS.replace("/* RITUAL */", methods).replace("/* PROVIDER */",
                polling.replace("var filter = getOrBuildOutputFilter();",
                        "var filter = getOrBuildOutputFilter(); if (filter.isEmpty()) return;")));

        expectFailure(HARNESS.replace("/* RITUAL */", methods).replace("/* PROVIDER */",
                polling.replace("} else {", "} else if (!filter.isEmpty()) {")));
        String wireless = extract(provider, Set.of("autoReturnWireless"), -1);
        expectFailure(HARNESS.replace("/* RITUAL */", methods).replace("/* PROVIDER */",
                polling.replace(wireless, wireless.replace(
                        "if (filter.isEmpty() && !adapter.supportsPatternIndependentHarvest())",
                        "if (false)"))));

        // Extraction's OFF guard must run before any world lookup or mutation.
        String extraction = extract(ritual, Set.of("extractOutputs"), 5);
        int guard = extraction.indexOf("if (scope.getReturnMode() != ReturnMode.AUTO)");
        if (guard < 0 || guard > extraction.indexOf("level.getBlockEntity(mainPos)")) {
            throw new AssertionError("OFF must guard extraction before world access");
        }
        String plan = extract(ritual, Set.of("planWithBinding"), 7);
        if (!plan.contains("!canDispatch(level, mainPos, handle, scope)")
                || !plan.contains("boolean autoHarvest = scope.getReturnMode() == ReturnMode.AUTO;")
                || !plan.contains("autoHarvest ? patternItemOutputs(pattern) : List.<ItemStack>of()")
                || !plan.contains("preexistingEntities, outputBowls, bowlBaselines, autoHarvest")) {
            throw new AssertionError("dispatch must guard ownership and snapshot harvest policy");
        }
        System.out.println("PASS: ritual lifecycle, empty-pattern polling, and original-bug mutations");
    }

    @Test
    void adjacentTargetRemovalBehavior() throws Exception {
        String provider = Files.readString(BASE.resolve("PackagedPatternProviderLogic.java"));
        String host = Files.readString(BASE.resolve("../blockentity/PackagedPatternProviderBlockEntity.java"));
        String methods = extract(provider,
                Set.of("onNeighborChanged", "clearRemovedAdjacentTargetState"), -1);
        String source = NEIGHBOR_HARNESS.replace("/* PROVIDER */", methods)
                .replace("/* HOST */", extract(host, Set.of("clearFlagsForTarget"), -1));
        run(source);
        expectFailure(source.replace("clearRemovedAdjacentTargetState();", ""));
        expectFailure(source.replace("if (!level.isLoaded(pos))", "if (false)"));
        expectFailure(source.replace("if (!remains)", "if (true)"));
    }

    private static String extract(String source, Set<String> names, int arity) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var input = new SimpleJavaFileObject(URI.create("string:///Source.java"),
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
            var selected = new StringBuilder();
            int count = 0;
            for (var member : type.getMembers()) {
                if (member instanceof MethodTree method && names.contains(method.getName().toString())
                        && (arity < 0 || method.getParameters().size() == arity)) {
                    selected.append(source, (int) positions.getStartPosition(unit, member),
                            (int) positions.getEndPosition(unit, member)).append('\n');
                    count++;
                }
            }
            if (count != names.size()) {
                throw new AssertionError("Production signatures changed; update lifecycle harness");
            }
            return selected.toString();
        }
    }

    private static void expectFailure(String source) throws Exception {
        try {
            run(source);
        } catch (AssertionError expected) {
            return;
        }
        throw new AssertionError("Original bug mutation was not detected");
    }

    private static void run(String source) throws Exception {
        Path temp = Files.createTempDirectory("packaged-ritual-lifecycle-");
        try {
            Path file = temp.resolve("LifecycleHarness.java");
            Files.writeString(file, source);
            if (ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "-proc:none", "-d", temp.toString(), file.toString()) != 0) {
                throw new IllegalStateException("Lifecycle harness failed to compile");
            }
            try (var loader = new URLClassLoader(new java.net.URL[] {temp.toUri().toURL()})) {
                try {
                    loader.loadClass("LifecycleHarness").getMethod("run").invoke(null);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    if (failure.getCause() instanceof AssertionError assertion) {
                        throw assertion;
                    }
                    throw failure;
                }
            }
        } finally {
            try (var paths = Files.walk(temp)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private static final String NEIGHBOR_HARNESS = """
        import java.util.*;
        public class LifecycleHarness {
            enum ProviderMode { NORMAL, WIRELESS }
            enum Direction { DOWN, UP, NORTH, SOUTH, WEST, EAST }
            record BlockPos(long value) {
                BlockPos relative(Direction face) { return new BlockPos(value + face.ordinal() + 1); }
                long asLong() { return value; }
            }
            static class ServerLevel {
                Map<BlockPos, String> blocks = new HashMap<>();
                Set<BlockPos> unloaded = new HashSet<>();
                boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos); }
                String getBlockEntity(BlockPos pos) {
                    check(isLoaded(pos), "must not look up an unloaded target");
                    return blocks.get(pos);
                }
            }
            interface Scope { void clearFlagsForTarget(BlockPos pos); }
            static class Host implements Scope {
                ProviderMode mode = ProviderMode.NORMAL;
                Object level = new ServerLevel();
                Map<String, HashSet<Long>> adapterFlags = new HashMap<>();
                Map<String, HashMap<Long, String>> adapterStates = new HashMap<>();
                int saves;
                ProviderMode getProviderMode() { return mode; }
                Object getLevel() { return level; }
                BlockPos getBlockPos() { return new BlockPos(0); }
                void saveChanges() { saves++; }
                void own(BlockPos pos) {
                    adapterFlags.computeIfAbsent("ritual:dispatched", k -> new HashSet<>()).add(pos.asLong());
                    adapterStates.computeIfAbsent("ritual:harvest", k -> new HashMap<>())
                            .put(pos.asLong(), "unpaid-auto-debt");
                }
                boolean owns(BlockPos pos) {
                    return adapterFlags.values().stream().anyMatch(v -> v.contains(pos.asLong()))
                            && adapterStates.values().stream().anyMatch(v -> v.containsKey(pos.asLong()));
                }
                /* HOST */
            }
            record Adapter(String kind) {
                boolean recognizesMain(ServerLevel level, BlockPos pos, String be) {
                    return kind.equals(be);
                }
            }
            record Registration(Adapter adapter, boolean enabled) {}
            static class MultiblockAdapterRegistry {
                static List<Registration> entries = new ArrayList<>();
                static List<Registration> registrations() { return entries; }
            }
            static class NeighborIndex {
                Map<Direction, Adapter> cache = new EnumMap<>(Direction.class);
                boolean dirty;
                Adapter getAdapter(Direction face) { return cache.get(face); }
                void invalidate() { dirty = true; }
            }
            static class BindingTable {
                boolean valid = true;
                void invalidateAll() { valid = false; }
            }
            static class BaseProvider {
                int notifications;
                public void onNeighborChanged() { notifications++; }
            }
            static class Provider extends BaseProvider {
                Host host = new Host();
                NeighborIndex neighborIndex = new NeighborIndex();
                BindingTable bindingTable = new BindingTable();
                Set<String> cooldownTable = new HashSet<>();
                Host getProviderHost() { return host; }
                Scope adapterScope() { return host; }
                /* PROVIDER */
            }
            static void check(boolean condition, String message) {
                if (!condition) throw new AssertionError(message);
            }
            public static void run() {
                var ritual = new Adapter("golden-bowl");
                var other = new Adapter("other-machine");
                MultiblockAdapterRegistry.entries.add(new Registration(ritual, false));
                MultiblockAdapterRegistry.entries.add(new Registration(other, true));
                var p = new Provider();
                var level = (ServerLevel) p.host.level;
                var target = p.host.getBlockPos().relative(Direction.EAST);
                var unrelated = p.host.getBlockPos().relative(Direction.WEST);
                var distant = new BlockPos(100);
                level.blocks.put(target, "golden-bowl");
                level.blocks.put(unrelated, "other-machine");
                p.neighborIndex.cache.put(Direction.EAST, ritual);
                p.host.own(target); p.host.own(unrelated); p.host.own(distant);
                p.onNeighborChanged();
                check(p.host.owns(target) && p.host.saves == 0, "unchanged target retains debt");
                check(!p.bindingTable.valid && p.neighborIndex.dirty && p.notifications == 1,
                        "normal neighbor invalidation still runs");

                level.blocks.remove(target); // normal break event precedes replacement
                p.onNeighborChanged();
                check(!p.host.owns(target) && p.host.saves == 1, "break retires and persists debt");
                check(p.host.owns(unrelated) && p.host.owns(distant), "cleanup is target-scoped");
                level.blocks.put(target, "golden-bowl");
                p.onNeighborChanged();
                check(!p.host.owns(target), "replacement must not inherit old debt");
                p.host.own(target); // a new craft can now own this position
                p.onNeighborChanged();
                check(p.host.owns(target), "new job survives ordinary updates");

                level.unloaded.add(target);
                level.blocks.remove(target);
                p.onNeighborChanged();
                check(p.host.owns(target), "unloaded target retains flags and state");
                level.blocks.put(target, "golden-bowl");
                level.unloaded.clear();
                p.onNeighborChanged();
                check(p.host.owns(target), "reload of same target retains debt");

                level.blocks.remove(unrelated);
                p.onNeighborChanged();
                check(p.host.owns(target) && !p.host.owns(unrelated), "unrelated neighbor removal is isolated");

                // No patterns or bindings, including a cold index after provider reload.
                p.neighborIndex.cache.clear();
                p.bindingTable.invalidateAll();
                p.onNeighborChanged();
                check(p.host.owns(target), "cold disabled registration still recognizes existing target");
                level.blocks.remove(target);
                p.onNeighborChanged();
                check(!p.host.owns(target), "no-pattern cold-index removal clears debt");
                check(p.host.adapterFlags.values().stream().noneMatch(v -> v.contains(target.asLong()))
                        && p.host.adapterStates.values().stream().noneMatch(v -> v.containsKey(target.asLong())),
                        "both flags and values must be cleared");
                level.blocks.put(target, "golden-bowl");
                p.onNeighborChanged();
                check(!p.host.owns(target), "cold-index remove then replace stays debt-free");

                p.host.own(target);
                p.neighborIndex.cache.put(Direction.EAST, ritual);
                level.blocks.put(target, "other-machine");
                p.onNeighborChanged();
                check(!p.host.owns(target), "known target replaced by another adapter clears debt");

                p.host.own(target);
                p.host.mode = ProviderMode.WIRELESS;
                level.blocks.remove(target);
                p.onNeighborChanged();
                check(p.host.owns(target), "local updates must not clear wireless ownership");
                p.host.mode = ProviderMode.NORMAL;
                p.host.level = null;
                p.onNeighborChanged();
                check(p.host.owns(target), "missing server level preserves ownership");
            }
        }
        """;

    private static final String HARNESS = """
        import java.util.*;
        public class LifecycleHarness {
            enum ReturnMode { OFF, AUTO }
            enum ProviderMode { NORMAL, WIRELESS }
            record BlockPos(int value) {
                BlockPos relative(int face) { return new BlockPos(value + face); }
            }
            record Dimension(String location) {}
            record GenericStack(AEItemKey what, long amount) {}
            record AEItemKey() { static AEItemKey of(ItemStack stack) { return new AEItemKey(); } }
            static class IActionSource {}
            static class ItemStack {
                int count;
                ItemStack(int count) { this.count = count; }
                int getCount() { return count; }
                boolean isEmpty() { return count == 0; }
                ItemStack copy() { return new ItemStack(count); }
                void setCount(int count) { this.count = count; }
                void shrink(int count) { this.count -= count; }
                static boolean isSameItemSameTags(ItemStack a, ItemStack b) { return true; }
            }
            static class Handler {
                ItemStack stack = new ItemStack(1);
                int getSlots() { return 1; }
                ItemStack getStackInSlot(int slot) { return stack; }
                ItemStack extractItem(int slot, int count, boolean simulate) {
                    int amount = Math.min(count, stack.count);
                    if (!simulate) stack.shrink(amount);
                    return new ItemStack(amount);
                }
                ItemStack insertItem(int slot, ItemStack value, boolean simulate) { return value; }
            }
            static class ItemEntity {
                ItemStack stack = new ItemStack(1);
                UUID id = UUID.randomUUID();
                boolean isAlive() { return !stack.isEmpty(); }
                ItemStack getItem() { return stack; }
                UUID getUUID() { return id; }
                void discard() { stack = new ItemStack(0); }
                void setItem(ItemStack value) { stack = value; }
            }
            static class Bowl {
                boolean idle;
                Object getBlockState() { return null; }
            }
            static class ServerLevel {
                Bowl bowl = new Bowl();
                List<ItemEntity> entities = new ArrayList<>();
                Set<BlockPos> unloaded = new HashSet<>();
                Map<BlockPos, MultiblockAdapter> adapters = new HashMap<>();
                Server server = new Server();
                Server getServer() { return server; }
                boolean isLoaded(BlockPos pos) { return !unloaded.contains(pos); }
                List<ItemEntity> getEntitiesOfClass(Class<ItemEntity> type, Object bounds) { return entities; }
                Dimension dimension() { return new Dimension("overworld"); }
                Bowl getBlockEntity(BlockPos pos) { return bowl; }
            }
            record Connection(String dimension, BlockPos pos) {}
            static class Server {
                Map<String, ServerLevel> levels = new HashMap<>();
                ServerLevel getLevel(String dimension) { return levels.get(dimension); }
            }
            static class MultiblockAdapterRegistry {
                static MultiblockAdapter find(ServerLevel level, BlockPos pos, Bowl be) {
                    return level.adapters.get(pos);
                }
            }
            static class OccultismReflection {
                static boolean isIdle(Bowl bowl) { return bowl.idle; }
                static boolean isSacrificialBowl(Bowl bowl) { return true; }
                static Handler itemHandler(Bowl bowl) { return null; }
            }
            static class OccultismHarvestState {
                final boolean auto, complete;
                OccultismHarvestState(boolean auto, boolean complete) {
                    this.auto = auto; this.complete = complete;
                }
                boolean autoHarvest() { return auto; }
                boolean complete() { return complete; }
                List<BlockPos> outputBowls() { return List.of(); }
                Map<UUID, Integer> preexistingEntityCounts() { return Map.of(); }
                int claimableFromBowl(BlockPos pos, ItemStack stack) { return claimable(stack, stack.count); }
                int claimable(ItemStack stack, int amount) { return auto && !complete ? Math.min(1, amount) : 0; }
                static int attributableCount(int count, int baseline) { return Math.max(0, count - baseline); }
                OccultismHarvestState consume(ItemStack stack, int amount) {
                    return new OccultismHarvestState(auto, amount > 0);
                }
                String encode() { return auto ? (complete ? "done" : "auto") : "off"; }
                static OccultismHarvestState decode(String value, String dimension) {
                    if ("off".equals(value)) return new OccultismHarvestState(false, true);
                    if ("auto".equals(value)) return new OccultismHarvestState(true, false);
                    if ("done".equals(value)) return new OccultismHarvestState(true, true);
                    return null;
                }
            }
            static class AdapterPersistentScope {
                ReturnMode mode = ReturnMode.OFF;
                Map<String, String> states = new HashMap<>();
                Set<String> flags = new HashSet<>();
                ReturnMode getReturnMode() { return mode; }
                void setState(BlockPos pos, String key, String value) { states.put(key, value); }
                String getState(BlockPos pos, String key) { return states.get(key); }
                boolean hasFlag(BlockPos pos, String key) { return flags.contains(key); }
            }
            static class AllowedOutputFilter {
                boolean empty = true;
                boolean isEmpty() { return empty; }
            }
            static class MultiblockAdapter {
                int polls;
                boolean supportsPatternIndependentHarvest() { return false; }
                void tickPending(ServerLevel l, BlockPos p, AdapterPersistentScope s) {}
                boolean canDispatch(ServerLevel l, BlockPos p, Object h, AdapterPersistentScope s) { return false; }
                List<GenericStack> extractOutputs(ServerLevel l, BlockPos p, AllowedOutputFilter f,
                        IActionSource source, AdapterPersistentScope scope) { polls++; return List.of(); }
            }
            static class VirtualCraftingAdapter extends MultiblockAdapter {}
            static class Ritual extends MultiblockAdapter {
                static final String RITUAL_HARVEST_STATE = "legacy-state";
                static final String RITUAL_DISPATCHED_FLAG = "legacy-flag";
                static final Log LOG = new Log();
                static class Log {
                    void warn(String message, Object... args) {}
                    void error(String message, Object... args) {}
                }
                Object ritualOutputAabb(BlockPos pos) { return null; }
                boolean isUpsideDownBowl(Object state) { return true; }
                String harvestStateKey(ServerLevel l) { return "state"; }
                String dispatchedFlagKey(ServerLevel l) { return "flag"; }
                boolean recognizesMain(ServerLevel l, BlockPos p, Bowl b) { return true; }
                void logStaleState(ServerLevel l, BlockPos p, OccultismHarvestState s) {}
                void clearHarvestState(ServerLevel l, BlockPos p, AdapterPersistentScope s) {
                    s.states.clear(); s.flags.clear();
                }
                boolean canDispatch(ServerLevel l, BlockPos p, Object h) {
                    return l.bowl != null && l.bowl.idle;
                }
                /* RITUAL */
            }
            static class Host {
                ProviderMode mode = ProviderMode.NORMAL;
                ProviderMode getProviderMode() { return mode; }
                BlockPos getBlockPos() { return new BlockPos(0); }
            }
            static class NeighborIndex {
                List<MultiblockAdapter> adapters = new ArrayList<>();
                List<Integer> adapterFaces(ServerLevel l) {
                    return java.util.stream.IntStream.range(0, adapters.size()).boxed().toList();
                }
                MultiblockAdapter getAdapter(int face) { return adapters.get(face); }
            }
            static class Provider {
                Host host = new Host();
                NeighborIndex neighborIndex = new NeighborIndex();
                AdapterPersistentScope scope = new AdapterPersistentScope();
                AllowedOutputFilter filter = new AllowedOutputFilter();
                List<Connection> connections = new ArrayList<>();
                List<Connection> getValidConnections(ServerLevel level, long tick) { return connections; }
                int delivered;
                Host getProviderHost() { return host; }
                AdapterPersistentScope adapterScope() { return scope; }
                IActionSource getActionSource() { return null; }
                AllowedOutputFilter getOrBuildOutputFilter() { return filter; }
                List<GenericStack> insertOutputsToReturnInv(List<GenericStack> outputs) {
                    delivered += outputs.size(); return List.of();
                }
                void enqueueVirtualOutputs(List<GenericStack> outputs) {}
                /* PROVIDER */
            }
            static void check(boolean condition, String message) {
                if (!condition) throw new AssertionError(message);
            }
            public static void run() {
                var adapter = new Ritual();
                var level = new ServerLevel();
                var pos = new BlockPos(0);
                var scope = new AdapterPersistentScope();
                level.bowl.idle = true;
                check(adapter.canDispatch(level, pos, null, scope), "initial OFF dispatch");
                scope.states.put("state", "off"); scope.flags.add("flag");
                level.bowl.idle = false;
                check(!adapter.canDispatch(level, pos, null, scope), "busy OFF blocks");
                check(scope.states.containsKey("state"), "busy OFF retains marker");
                level.bowl.idle = true;
                check(adapter.canDispatch(level, pos, null, scope), "OFF external collection permits next dispatch");
                check(scope.states.isEmpty() && scope.flags.isEmpty(), "OFF cleanup retires marker");

                // Reloaded OFF state is never converted into AUTO ownership.
                scope.states.put("state", "off"); scope.mode = ReturnMode.AUTO;
                level.bowl.idle = false;
                adapter.tickPending(level, pos, scope);
                check("off".equals(scope.states.get("state")), "OFF to AUTO while busy");
                level.bowl.idle = true;
                adapter.tickPending(level, pos, scope);
                check(scope.states.isEmpty(), "OFF to AUTO retires without adopting products");

                scope.states.put("state", "auto"); scope.flags.add("flag");
                scope.mode = ReturnMode.OFF;
                adapter.tickPending(level, pos, scope);
                check(!adapter.canDispatch(level, pos, null, scope), "AUTO to OFF preserves unpaid debt");
                check("auto".equals(scope.states.get("state")), "external collection cannot erase AUTO debt");
                scope.mode = ReturnMode.AUTO;
                check(!adapter.canDispatch(level, pos, null, scope), "AUTO resumes same reserved job");
                scope.states.put("state", "done");
                check(adapter.canDispatch(level, pos, null, scope), "paid AUTO job permits next dispatch");
                for (String encoded : List.of("bad", "auto")) {
                    scope.states.put("state", encoded);
                    adapter.tickPending(level, pos, scope);
                    check(!adapter.canDispatch(level, pos, null, scope), "unknown or unfinished state blocks");
                }
                scope.states.clear(); scope.flags.clear(); scope.flags.add("legacy-flag");
                check(!adapter.canDispatch(level, pos, null, scope), "legacy flag blocks overwrite");
                scope.states.put("state", "off");
                adapter.tickPending(level, pos, scope);
                check(scope.states.containsKey("state"), "conflicting legacy marker retained");

                scope.states.clear(); scope.flags.clear(); scope.states.put("state", "off");
                scope.mode = ReturnMode.OFF;
                var oldOutput = new ItemEntity(); level.entities.add(oldOutput);
                check(adapter.extractOutputs(level, pos, null, null, scope).isEmpty(), "OFF never extracts");
                check(oldOutput.isAlive() && scope.states.containsKey("state"), "OFF extraction changes nothing");
                scope.mode = ReturnMode.AUTO;
                check(adapter.extractOutputs(level, pos, null, null, scope).isEmpty(), "OFF to AUTO cannot adopt old output");
                check(oldOutput.isAlive() && scope.states.isEmpty(), "old output remains externally owned");
                check(adapter.extractOutputs(level, pos, null, null, scope).isEmpty(), "no state means no ownership");
                level.entities.clear();
                var paidOutput = new ItemEntity(); level.entities.add(paidOutput);
                scope.states.put("state", "auto"); scope.mode = ReturnMode.OFF;
                check(adapter.extractOutputs(level, pos, null, null, scope).isEmpty(), "AUTO to OFF pauses harvest");
                check(paidOutput.isAlive() && "auto".equals(scope.states.get("state")), "paused debt survives");
                scope.mode = ReturnMode.AUTO;
                check(adapter.extractOutputs(level, pos, null, null, scope).size() == 1, "AUTO resumes owned harvest");
                check(!paidOutput.isAlive() && scope.states.isEmpty(), "harvest clears paid debt");

                var provider = new Provider();
                adapter = new Ritual() {
                    @Override public List<GenericStack> extractOutputs(ServerLevel l, BlockPos p,
                            AllowedOutputFilter f, IActionSource source, AdapterPersistentScope s) {
                        polls++;
                        return super.extractOutputs(l, p, f, source, s);
                    }
                };
                provider.scope.mode = ReturnMode.AUTO;
                var ordinary = new MultiblockAdapter();
                var virtual = new VirtualCraftingAdapter();
                provider.neighborIndex.adapters.addAll(List.of(adapter, ordinary, virtual));
                provider.filter.empty = false;
                provider.runAutoReturnTick(level, 0);
                check(adapter.polls == 1 && ordinary.polls == 1 && virtual.polls == 0, "normal pattern polling");
                provider.filter.empty = true; // last pattern removed, including after provider reload
                provider.scope.states.put("state", "auto");
                level.entities.clear(); level.entities.add(new ItemEntity());
                provider.runAutoReturnTick(level, 20);
                check(adapter.polls == 2, "owned pending job survives last pattern removal");
                check(provider.delivered == 1 && provider.scope.states.isEmpty()
                        && !level.entities.get(0).isAlive(), "empty-pattern poll finishes persisted paid job");
                check(ordinary.polls == 1 && virtual.polls == 0, "empty filter cannot enable unrelated extraction");
                provider.host.mode = ProviderMode.WIRELESS;
                var remote = new ServerLevel();
                level.server.levels.put("remote", remote);
                var ritualPos = new BlockPos(10);
                var ordinaryPos = new BlockPos(11);
                var virtualPos = new BlockPos(12);
                var unknownPos = new BlockPos(13);
                remote.adapters.put(ritualPos, adapter);
                remote.adapters.put(ordinaryPos, ordinary);
                remote.adapters.put(virtualPos, virtual);
                provider.connections.addAll(List.of(new Connection("remote", ritualPos),
                        new Connection("remote", ordinaryPos), new Connection("remote", virtualPos),
                        new Connection("remote", unknownPos), new Connection("missing", ritualPos)));
                provider.scope.states.put("state", "auto"); // restored paid job with no patterns
                remote.entities.add(new ItemEntity());
                provider.runAutoReturnTick(level, 40);
                check(adapter.polls == 3 && provider.delivered == 1
                        && "auto".equals(provider.scope.states.get("state")), "wireless busy job retains debt");
                remote.bowl.idle = true;
                remote.unloaded.add(ritualPos);
                provider.runAutoReturnTick(level, 60);
                check(adapter.polls == 3 && remote.entities.get(0).isAlive(), "unloaded wireless job is not touched");
                remote.unloaded.clear();
                provider.runAutoReturnTick(level, 80);
                check(adapter.polls == 4 && provider.delivered == 2 && provider.scope.states.isEmpty()
                        && !remote.entities.get(0).isAlive(), "wireless empty-pattern poll finishes remote paid job");
                check(ordinary.polls == 1 && virtual.polls == 0,
                        "wireless empty filter cannot enable unrelated or virtual extraction");
                remote.entities.add(new ItemEntity());
                provider.runAutoReturnTick(level, 100);
                check(provider.delivered == 2 && remote.entities.get(1).isAlive(),
                        "wireless poll without ownership leaves output alone");
                provider.filter.empty = false;
                provider.runAutoReturnTick(level, 120);
                check(ordinary.polls == 2 && virtual.polls == 0, "wireless nonempty-filter behavior preserved");
                provider.connections.clear();
                int pollsBefore = adapter.polls;
                provider.runAutoReturnTick(level, 140);
                check(adapter.polls == pollsBefore, "no wireless connections means no extraction");
            }
        }
        """;
}
