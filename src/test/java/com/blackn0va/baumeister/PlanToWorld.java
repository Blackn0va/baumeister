package com.blackn0va.baumeister;

import com.blackn0va.baumeister.plan.CompiledBuild;
import com.blackn0va.baumeister.plan.Orientation;
import com.blackn0va.baumeister.plan.Orientation.Cardinal;
import com.blackn0va.baumeister.plan.PlanCompiler;
import com.blackn0va.baumeister.plan.PlanParser;
import com.blackn0va.baumeister.plan.Pos;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Prüfhilfe für den Testserver: rechnet eine gespeicherte LLM-Antwort (plugins/Baumeister/pläne)
 * in Weltblöcke um und gibt "x y z id" je Zeile aus. Die Verschiebung ist bewusst hier noch einmal
 * von Hand geschrieben und nicht aus BuildService geholt – sonst prüfte der Test sich selbst.
 *
 *   java -cp "target/classes;target/test-classes;lib/*" com.blackn0va.baumeister.PlanToWorld plan.json x y z n|o|s|w [abstand]
 */
public final class PlanToWorld {

    public static void main(String[] args) throws Exception {
        String json = Files.readString(Path.of(args[0]), StandardCharsets.UTF_8);
        String answer = JsonParser.parseString(json).getAsJsonObject().get("antwort").getAsString();
        int ox = Integer.parseInt(args[1]);
        int oy = Integer.parseInt(args[2]);
        int oz = Integer.parseInt(args[3]);
        Cardinal facing = Cardinal.parse(args[4]);
        int distance = args.length > 5 ? Integer.parseInt(args[5]) : 3;
        PlanCompiler compiler = new PlanCompiler(
                id -> org.bukkit.Material.getMaterial(id.toUpperCase(Locale.ROOT)) != null,
                "stone", new PlanCompiler.Limits(6000, 48));
        CompiledBuild b = compiler.compile(PlanParser.parse(answer));
        Orientation o = new Orientation(facing);
        int shiftX = -Math.floorDiv(b.min().x() + b.max().x(), 2);
        int shiftZ = distance - b.min().z();
        Map<Pos, String> out = new LinkedHashMap<>();
        b.actions().forEach(a -> a.changes().forEach(c -> {
            Pos w = o.toWorldOffset(c.pos().add(shiftX, 0, shiftZ));
            out.put(new Pos(ox + w.x(), oy + w.y(), oz + w.z()), c.block().id());
        }));
        out.forEach((p, id) -> System.out.println(p.x() + " " + p.y() + " " + p.z() + " " + id));
    }
}
