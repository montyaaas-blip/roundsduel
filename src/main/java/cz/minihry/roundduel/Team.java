package cz.minihry.roundduel;

import org.bukkit.Color;

/** Dva týmy, mezi kterými se hráči vyvažují. */
public enum Team {

    RED("Červení", "red", "#ff5555", Color.fromRGB(0xCC3333)),
    BLUE("Modří", "blue", "#5599ff", Color.fromRGB(0x3366CC));

    private final String displayName;
    private final String key;
    private final String hex;
    private final Color armorColor;

    Team(String displayName, String key, String hex, Color armorColor) {
        this.displayName = displayName;
        this.key = key;
        this.hex = hex;
        this.armorColor = armorColor;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public Color armorColor() {
        return armorColor;
    }

    /** MiniMessage tag pro barvu týmu, např. "<color:#ff5555>". */
    public String tag() {
        return "<color:" + hex + ">";
    }

    public Team other() {
        return this == RED ? BLUE : RED;
    }

    public static Team byKey(String key) {
        for (Team team : values()) {
            if (team.key.equalsIgnoreCase(key) || team.name().equalsIgnoreCase(key)) {
                return team;
            }
        }
        return null;
    }
}
