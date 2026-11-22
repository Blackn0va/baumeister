package com.blackn0va.baumeister.town.use;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bewohner-Körper, der jede Bewegung mitschreibt („lie x y z yaw“, „sit …“, „rise …“, „swing“, „effect sound“). */
public final class FakeActor implements Actor {

    public final UUID id = UUID.randomUUID();
    public final List<String> calls = new ArrayList<>();
    public double x;
    public double y;
    public double z;
    /** Gesetzt: sit klappt nicht (Spawn abgesagt). */
    public boolean seatRefused;

    public FakeActor(double x, double y, double z) {
        move(x, y, z);
    }

    public FakeActor move(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    @Override
    public UUID id() {
        return id;
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double z() {
        return z;
    }

    @Override
    public void face(double x, double y, double z) {
        calls.add("face");
    }

    @Override
    public void swing() {
        calls.add("swing");
    }

    @Override
    public void lie(double x, double y, double z, float yaw) {
        calls.add(String.format(java.util.Locale.ROOT, "lie %.4f %.4f %.4f %.0f", x, y, z, yaw));
        move(x, y, z);
    }

    @Override
    public Runnable sit(double x, double y, double z, float yaw) {
        if (seatRefused) {
            calls.add("sit refused");
            return null;
        }
        calls.add(String.format(java.util.Locale.ROOT, "sit %.2f %.2f %.2f %.0f", x, y, z, yaw));
        move(x, y, z);
        return () -> calls.add("unseat");
    }

    @Override
    public void rise(double x, double y, double z) {
        calls.add(String.format(java.util.Locale.ROOT, "rise %.1f %.1f %.1f", x, y, z));
        move(x, y, z);
    }

    @Override
    public void effect(String sound, String particle, double x, double y, double z) {
        calls.add("effect " + sound + " " + particle);
    }

    public long count(String prefix) {
        return calls.stream().filter(c -> c.startsWith(prefix)).count();
    }
}
