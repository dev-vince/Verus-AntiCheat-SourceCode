package me.levansj01.verus.check.checks.badpackets;


import me.levansj01.verus.check.PacketCheck;
import me.levansj01.verus.check.annotation.CheckInfo;
import me.levansj01.verus.check.type.CheckType;
import me.levansj01.verus.check.version.CheckVersion;
import me.levansj01.verus.compat.VPacket;
import me.levansj01.verus.compat.packets.VPacketPlayInBlockDig;

@CheckInfo(type = CheckType.BAD_PACKETS, subType = "D", friendlyName = "KillAura", version = CheckVersion.RELEASE, maxViolations = 1)
public class BadPacketsD extends PacketCheck {

    public void handle(VPacket vPacket, long l) {
        VPacketPlayInBlockDig vPacketPlayInBlockDig;
        if (vPacket instanceof VPacketPlayInBlockDig && (vPacketPlayInBlockDig = (VPacketPlayInBlockDig) vPacket).getType() == VPacketPlayInBlockDig.PlayerDigType.RELEASE_USE_ITEM) {
            switch (vPacketPlayInBlockDig.getFace()) {
                case UP:
                case NORTH:
                case EAST:
                case WEST: {
                    boolean displayFlag = this.violations < 2.0;
                    this.handleViolation(BadPacketsD.handle(vPacketPlayInBlockDig), 1.0, displayFlag);
                }
            }
        }
    }

    private static String handle(VPacketPlayInBlockDig vPacketPlayInBlockDig) {
        return String.format("F: %s", vPacketPlayInBlockDig.getFace().name());
    }
}
