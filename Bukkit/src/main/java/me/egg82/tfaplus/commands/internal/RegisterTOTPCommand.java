package me.egg82.tfaplus.commands.internal;

import co.aikar.taskchain.TaskChain;
import co.aikar.taskchain.TaskChainAbortAction;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import me.egg82.tfaplus.APIException;
import me.egg82.tfaplus.TFAAPI;
import me.egg82.tfaplus.extended.Configuration;
import me.egg82.tfaplus.services.lookup.PlayerLookup;
import me.egg82.tfaplus.utils.ConfigUtil;
import me.egg82.tfaplus.utils.LogUtil;
import me.egg82.tfaplus.utils.MapUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RegisterTOTPCommand implements Runnable {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final TaskChain<?> chain;
    private final CommandSender sender;
    private final String playerName;

    private final TFAAPI api = TFAAPI.getInstance();

    public RegisterTOTPCommand(TaskChain<?> chain, CommandSender sender, String playerName) {
        this.chain = chain;
        this.sender = sender;
        this.playerName = playerName;
    }

    public void run() {
        if (!sender.getName().equals(playerName) && !sender.hasPermission("2faplus.admin")) {
            sender.sendMessage(LogUtil.getHeading() + ChatColor.DARK_RED + "You need the \"2faplus.admin\" permission node to register other players.");
            return;
        }

        sender.sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "Registering " + ChatColor.WHITE + playerName + ChatColor.YELLOW + ", please wait..");

        chain
                .<UUID>asyncCallback((v, f) -> f.accept(getUuid(playerName)))
                .abortIfNull(new TaskChainAbortAction<Object, Object, Object>() {
                    @Override
                    public void onAbort(TaskChain<?> chain, Object arg1) {
                        sender.sendMessage(ChatColor.DARK_RED + "Could not get UUID for " + ChatColor.WHITE + playerName + ChatColor.DARK_RED + " (rate-limited?)");
                    }
                })
                .<String>asyncCallback((v, f) -> {
                    Optional<Configuration> config = ConfigUtil.getConfig();
                    if (!config.isPresent()) {
                        f.accept(null);
                        return;
                    }

                    try {
                        f.accept(api.registerTOTP(v, config.get().getNode("otp", "digits").getLong()));
                    } catch (APIException ex) {
                        logger.error(ex.getMessage(), ex);
                    }
                })
                .abortIfNull(new TaskChainAbortAction<Object, Object, Object>() {
                    @Override
                    public void onAbort(TaskChain<?> chain, Object arg1) {
                        sender.sendMessage(LogUtil.getHeading() + LogUtil.getHeading() + ChatColor.YELLOW + "Internal error");
                    }
                })
                .syncLast(v -> {
                    if (v == null) {
                        sender.sendMessage(LogUtil.getHeading() + ChatColor.DARK_RED + "Could not register " + ChatColor.WHITE + playerName);
                        return;
                    }

                    Optional<Configuration> config = ConfigUtil.getConfig();
                    if (!config.isPresent()) {
                        return;
                    }

                    Player player = Bukkit.getPlayer(getUuid(playerName));

                    sender.sendMessage(LogUtil.getHeading() + ChatColor.WHITE + playerName + ChatColor.GREEN + " has been successfully registered.");
                    if (player == null || !(sender instanceof Player) || player.getUniqueId() != ((Player) sender).getUniqueId()) {
                        sender.sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "Their 2FA account key is " + ChatColor.WHITE + v);
                        if (sender instanceof Player) {
                            sender.sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "You have been provided a scannable QR code for your convenience.");
                            MapUtil.sendTOTPQRCode((Player) sender, v, config.get().getNode("otp", "issuer").getString(""), config.get().getNode("otp", "digits").getLong());
                        }
                        sender.sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "Please remember to keep this information PRIVATE!");
                    }

                    if (player != null) {
                        player.sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "Your 2FA account key is " + ChatColor.WHITE + v);
                        player.sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "You have been provided a scannable QR code for your convenience.");
                        MapUtil.sendTOTPQRCode(player, v, config.get().getNode("otp", "issuer").getString(""), config.get().getNode("otp", "digits").getLong());
                        player.sendMessage(LogUtil.getHeading() + ChatColor.YELLOW + "Please remember to keep this key and QR code PRIVATE!");
                    }
                })
                .execute();
    }

    private UUID getUuid(String name) {
        try {
            return PlayerLookup.get(name).getUUID();
        } catch (IOException ex) {
            logger.error(ex.getMessage(), ex);
            return null;
        }
    }
}
