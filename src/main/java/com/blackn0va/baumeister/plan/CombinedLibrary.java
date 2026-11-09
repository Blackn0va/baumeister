package com.blackn0va.baumeister.plan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Mehrere Bibliotheken hintereinander: eingebaute Maschinen zuerst, dann die WorldEdit-Bauteile. Gleichnamige
 * Schematics überdecken keine eingebaute Maschine – die ist geprüft, ein Schematic nicht.
 */
public record CombinedLibrary(List<ModuleLibrary> parts) implements ModuleLibrary {

    public CombinedLibrary {
        parts = List.copyOf(parts);
    }

    @Override
    public Optional<Module> find(String name) {
        for (ModuleLibrary lib : parts) {
            Optional<Module> m = lib.find(name);
            if (m.isPresent()) {
                return m;
            }
        }
        return Optional.empty();
    }

    @Override
    public List<Module> all() {
        List<Module> out = new ArrayList<>();
        for (ModuleLibrary lib : parts) {
            for (Module m : lib.all()) {
                if (out.stream().noneMatch(o -> o.name().equals(m.name()))) {
                    out.add(m);
                }
            }
        }
        return List.copyOf(out);
    }
}
