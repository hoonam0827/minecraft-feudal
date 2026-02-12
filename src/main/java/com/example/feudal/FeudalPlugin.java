package com.example.feudal;

import com.example.feudal.command.FeudalCommand;
import com.example.feudal.land.FamilyLandProtectListener;
import com.example.feudal.merchant.MerchantEditListener;
import com.example.feudal.merchant.MerchantKeys;
import com.example.feudal.merchant.MerchantPriceInputListener;
import com.example.feudal.merchant.MerchantService;
import com.example.feudal.merchant.MerchantShopStorage;
import com.example.feudal.npc.FeudalNPCTrait;
import com.example.feudal.npc.FarmerLoop;
import com.example.feudal.npc.MerchantBuyListener;
import com.example.feudal.npc.TaxCollectorLoop;
import com.example.feudal.service.FeudalService;
import com.example.feudal.service.TaxService;
import com.example.feudal.storage.Database;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.trait.TraitInfo;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import com.example.feudal.npc.GuardLoop;

public class FeudalPlugin extends JavaPlugin {

    private Database database;

    private FeudalService feudalService;
    private TaxService taxService;

    private MerchantKeys merchantKeys;
    private MerchantShopStorage merchantShopStorage;
    private MerchantService merchantService;

    @Override
    public void onEnable() {

        // Citizens 필수 체크
        if (Bukkit.getPluginManager().getPlugin("Citizens") == null) {
            getLogger().severe("Citizens 플러그인이 필요함! (Citizens 없음)");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // DB 오픈
        try {
            database = new Database(this);
            database.open();
        } catch (Exception e) {
            getLogger().severe("DB 오픈 실패: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        // 서비스 생성
        feudalService = new FeudalService(database);
        taxService = new TaxService(database);

        // 상인 시스템
        merchantKeys = new MerchantKeys(this);
        merchantShopStorage = new MerchantShopStorage(this);
        merchantService = new MerchantService(this, merchantShopStorage, merchantKeys);

        // Citizens Trait 등록
        try {
            CitizensAPI.getTraitFactory().registerTrait(
                    TraitInfo.create(FeudalNPCTrait.class).withName("feudal")
            );
        } catch (Throwable t) {
            getLogger().warning("FeudalNPCTrait 등록 스킵/실패: " + t.getMessage());
        }

        // 커맨드 등록
        if (getCommand("f") != null) {
            getCommand("f").setExecutor(new FeudalCommand(feudalService, taxService, merchantService));
        } else {
            getLogger().severe("plugin.yml에 command 'f' 등록이 안 되어있음!");
        }

        // 리스너 등록
        Bukkit.getPluginManager().registerEvents(new MerchantBuyListener(), this);

        // 상점 가격 입력(모루 GUI)
        Bukkit.getPluginManager().registerEvents(new MerchantPriceInputListener(this), this);

        // 상점 편집(shift+우클릭 채팅 입력 방식도 같이 켜두려면)
        Bukkit.getPluginManager().registerEvents(new MerchantEditListener(this, merchantService), this);

        // 영지 보호
        Bukkit.getPluginManager().registerEvents(new FamilyLandProtectListener(feudalService), this);

        // 루프 시작
        new FarmerLoop(this, feudalService).start();
        new TaxCollectorLoop(this, taxService, feudalService).start();
        new GuardLoop(this, feudalService).start();

        getLogger().info("Feudalism enabled!");
    }

    @Override
    public void onDisable() {
        try {
            if (database != null) database.close();
        } catch (Throwable ignored) {}

        getLogger().info("Feudalism disabled!");
    }

    public Database getDatabase() { return database; }
    public FeudalService getFeudalService() { return feudalService; }
    public TaxService getTaxService() { return taxService; }

    public MerchantService getMerchantService() { return merchantService; }
    public MerchantShopStorage getMerchantShopStorage() { return merchantShopStorage; }
    public MerchantKeys getMerchantKeys() { return merchantKeys; }
}