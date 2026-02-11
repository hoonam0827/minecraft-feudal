package com.example.feudal;

import com.example.feudal.command.FeudalCommand;
import com.example.feudal.npc.FeudalNPCTrait;
import com.example.feudal.npc.TaxCollectorLoop;
import com.example.feudal.service.FeudalService;
import com.example.feudal.service.TaxService;
import com.example.feudal.storage.Database;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.TraitInfo;
import org.bukkit.plugin.java.JavaPlugin;

public class FeudalPlugin extends JavaPlugin {
    private Database db;

    @Override
    public void onEnable() {
        try {
            CitizensAPI.getTraitFactory().registerTrait(
                    TraitInfo.create(FeudalNPCTrait.class).withName("feudal")
            );
        } catch (Throwable t) {
            getLogger().severe("Citizens Trait 등록 실패: " + t.getMessage());
        }

        try {
            db = new Database(this);
            db.open();
        } catch (Exception e) {
            getLogger().severe("DB 오픈 실패: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        FeudalService feudalService = new FeudalService(db);
        TaxService taxService = new TaxService(db);

        if (getCommand("f") != null) {
            getCommand("f").setExecutor(new FeudalCommand(feudalService, taxService));
        }

        new TaxCollectorLoop(this, taxService, feudalService).start();

        getLogger().info("Feudal enabled!");
    }

    @Override
    public void onDisable() {
        if (db != null) db.close();
    }
}