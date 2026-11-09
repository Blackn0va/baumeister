package com.blackn0va.baumeister.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.blackn0va.baumeister.plan.Orientation.Cardinal;

import org.junit.jupiter.api.Test;

class OrientationTest {

    @Test
    void lookingNorthRightIsEastAndForwardIsMinusZ() {
        Orientation o = new Orientation(Cardinal.NORTH);
        assertEquals(new Pos(1, 0, 0), o.toWorldOffset(new Pos(1, 0, 0)));
        assertEquals(new Pos(0, 0, -1), o.toWorldOffset(new Pos(0, 0, 1)));
    }

    @Test
    void lookingEastRightIsSouth() {
        Orientation o = new Orientation(Cardinal.EAST);
        assertEquals(new Pos(0, 0, 1), o.toWorldOffset(new Pos(1, 0, 0)));
        assertEquals(new Pos(1, 0, 0), o.toWorldOffset(new Pos(0, 0, 1)));
    }

    @Test
    void lookingSouthRightIsWest() {
        Orientation o = new Orientation(Cardinal.SOUTH);
        assertEquals(new Pos(-1, 2, 0), o.toWorldOffset(new Pos(1, 2, 0)));
    }

    @Test
    void frontDirectionPointsBackToThePlayer() {
        Orientation o = new Orientation(Cardinal.WEST);
        assertEquals(Cardinal.EAST, o.toWorld("front"));
        assertEquals(Cardinal.WEST, o.toWorld("back"));
        assertEquals(Cardinal.NORTH, o.toWorld("right"));
    }

    @Test
    void translatesFacingAndAxisInBlockStates() throws Exception {
        Orientation o = new Orientation(Cardinal.EAST);
        BlockSpec stairs = o.toWorld(BlockSpec.parse("oak_stairs[facing=left,half=top]"));
        assertEquals("north", stairs.props().get("facing"));
        assertEquals("top", stairs.props().get("half"));
        assertEquals("z", o.toWorld(BlockSpec.parse("oak_log[axis=x]")).props().get("axis"));
        assertEquals("y", o.toWorld(BlockSpec.parse("oak_log[axis=y]")).props().get("axis"));
        assertEquals("minecraft:oak_stairs[facing=north,half=top]", stairs.asString());
    }

    @Test
    void yawMapsToCardinal() {
        assertEquals(Cardinal.SOUTH, Cardinal.fromYaw(10));
        assertEquals(Cardinal.WEST, Cardinal.fromYaw(90));
        assertEquals(Cardinal.NORTH, Cardinal.fromYaw(-170));
        assertEquals(Cardinal.EAST, Cardinal.fromYaw(-90));
    }

    /**
     * Plan B3: Himmelsrichtungen in Werten und Schlüsseln gelten im Nordrahmen und drehen mit; toLocal behält sie. So
     * passt der reine Zwilling zu BlockData.rotate am Server.
     */
    @Test
    void compassKeysAndValuesTurnInTheNorthFrameAndSurviveTheRoundTrip() throws Exception {
        BlockSpec fence = BlockSpec.parse("oak_fence[north=true,east=false,south=false,west=true]");
        for (Cardinal f : Cardinal.values()) {
            Orientation o = new Orientation(f);
            assertEquals(fence, o.toLocal(o.toWorld(fence)), f.name());
        }
        Orientation east = new Orientation(Cardinal.EAST);
        BlockSpec world = east.toWorld(fence);
        assertEquals("true", world.props().get("east"), "north im Nordrahmen = hinten = Blickrichtung Ost");
        assertEquals("true", world.props().get("north"), "west → north");
        assertEquals(java.util.Set.of("north", "east", "south", "west"), world.explicit());
        assertEquals("east", east.toWorld(BlockSpec.parse("furnace[facing=north]")).props().get("facing"));
    }

    @Test
    void signRotationAndCrafterOrientationTurnWithTheBuild() throws Exception {
        assertEquals("4", new Orientation(Cardinal.EAST).toWorld(BlockSpec.parse("oak_sign[rotation=0]")).props()
                .get("rotation"));
        assertEquals("10", new Orientation(Cardinal.SOUTH).toWorld(BlockSpec.parse("oak_sign[rotation=2]")).props()
                .get("rotation"));
        assertEquals("east_up", new Orientation(Cardinal.EAST).toWorld(BlockSpec.parse(
                "crafter[orientation=north_up]")).props().get("orientation"));
        assertEquals("down_west", new Orientation(Cardinal.WEST).toWorld(BlockSpec.parse(
                "jigsaw[orientation=down_north]")).props().get("orientation"));
        assertEquals("12", Orientation.rotateLocal(BlockSpec.parse("oak_sign[rotation=8]"), 1).props()
                .get("rotation"), "Bauteil eine Viertelumdrehung weiter");
    }

    @Test
    void northFrameNamesLocalDirectionsWithoutTurning() throws Exception {
        assertEquals("west", Orientation.northFrame(BlockSpec.parse("repeater[facing=left]")).props().get("facing"));
        assertEquals("north_east", Orientation.northFrame(BlockSpec.parse("rail[shape=back_right]")).props()
                .get("shape"));
        assertEquals("true", Orientation.northFrame(BlockSpec.parse("oak_fence[north=true]")).props().get("north"));
        assertEquals("up", Orientation.northFrame(BlockSpec.parse("piston[facing=up]")).props().get("facing"));
    }
}
