package io.github.jdubois.bootui.inventorytest;

/**
 * Opt-in switch for the fixtures that stand in for an application in this module's inventory tests.
 *
 * <p>{@code bootui-micronaut} has no sample application of its own, so the Beans, Mappings and
 * error-contract inventories have nothing application-owned to describe here — which is exactly what the
 * three bugs these tests pin looked like from the inside: an inventory full of BootUI's and Micronaut's own
 * declarations. The fixtures in this package supply the missing half.
 *
 * <p>They deliberately live <em>outside</em> {@code io.github.jdubois.bootui.micronaut}, because that
 * package is what the adapter's self-filter hides; a fixture inside it would be filtered as console
 * furniture and prove nothing. They are also opt-in, so every other test in this module keeps booting the
 * bare adapter it documents.
 */
public final class InventoryTestFixtures {

    /** Set to {@code true} to publish the fixture controller and exception handler. */
    public static final String PROPERTY = "bootui.test.inventory";

    private InventoryTestFixtures() {}
}
