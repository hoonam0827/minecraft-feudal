package com.example.feudal.command;

import com.example.feudal.model.Job;
import com.example.feudal.model.Rank;
import com.example.feudal.npc.FeudalNPCTrait;
import com.example.feudal.npc.NPCRole;
import com.example.feudal.service.FeudalService;
import com.example.feudal.service.TaxService;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FeudalCommand implements CommandExecutor {
    private final FeudalService service;
    private final TaxService taxService;

    // ✅ 초대 저장(서버 재시작하면 초기화됨)
    private final Map<UUID, PendingInvite> invites = new HashMap<>();
    private static final long INVITE_EXPIRE_MS = 5 * 60 * 1000L; // 5분

    private record PendingInvite(int familyId, long expiresAtMs, String familyName) {}

    public FeudalCommand(FeudalService service, TaxService taxService) {
        this.service = service;
        this.taxService = taxService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("플레이어만 사용 가능");
            return true;
        }

        if (args.length == 0) {
            help(p);
            return true;
        }

        try {
            switch (args[0].toLowerCase()) {

                case "create" -> {
                    if (args.length < 2) { p.sendMessage("§c/f create <가문이름>"); return true; }
                    if (service.getFamilyIdOf(p.getUniqueId()).isPresent()) {
                        p.sendMessage("§c이미 가문에 소속돼 있어!");
                        return true;
                    }
                    String name = args[1];
                    if (service.getFamilyIdByName(name).isPresent()) {
                        p.sendMessage("§c이미 존재하는 가문이름!");
                        return true;
                    }
                    service.createFamily(name, p.getUniqueId());
                    p.sendMessage("§a가문 생성 완료! 너는 §6KING§a 이야.");
                }

                case "info" -> {
                    var fidOpt = service.getFamilyIdOf(p.getUniqueId());
                    if (fidOpt.isEmpty()) { p.sendMessage("§7무소속(평민)"); return true; }
                    Rank r = service.getRank(p.getUniqueId());
                    Job j = service.getJob(p.getUniqueId());
                    boolean serf = service.isSerf(p.getUniqueId());
                    p.sendMessage("§b내 계급: §f" + r.name());
                    p.sendMessage("§b내 직업: §f" + j.name());
                    p.sendMessage("§b농노 여부: " + (serf ? "§cON(농노)" : "§aOFF(자유민)"));
                    p.sendMessage("§b" + service.getFamilyInfoById(fidOpt.get()));
                }

                // ✅ /f fid (내 familyId 빠르게 확인)
                case "fid" -> {
                    var fidOpt = service.getFamilyIdOf(p.getUniqueId());
                    if (fidOpt.isEmpty()) {
                        p.sendMessage("§7너는 가문이 없어(평민).");
                        return true;
                    }
                    int familyId = fidOpt.get();
                    String name = service.getFamilyNameById(familyId);
                    p.sendMessage("§6[가문] §ffamilyId=§e" + familyId + " §7/ 이름=§a" + name);
                }

                // =========================
                // ✅ 영지
                // =========================
                case "land" -> {
                    var fidOpt = service.getFamilyIdOf(p.getUniqueId());
                    if (fidOpt.isEmpty()) { p.sendMessage("§7가문이 있어야 영지를 쓸 수 있어!"); return true; }
                    int familyId = fidOpt.get();

                    if (args.length < 2) {
                        p.sendMessage("§e/f land info");
                        p.sendMessage("§e/f land set [radius]     (KING만)");
                        p.sendMessage("§e/f land radius <n>       (KING만)");
                        p.sendMessage("§e/f land on|off           (KING만)");
                        return true;
                    }

                    String sub = args[1].toLowerCase();

                    if (sub.equals("info")) {
                        var landOpt = service.getFamilyLand(familyId);
                        if (landOpt.isEmpty()) {
                            p.sendMessage("§7설정된 영지가 없어. §e/f land set §7으로 설정해!");
                            return true;
                        }
                        var land = landOpt.get();
                        p.sendMessage("§6[영지] §a" + service.getFamilyNameById(familyId));
                        p.sendMessage("§7월드: §f" + land.world());
                        p.sendMessage("§7중심: §f" + land.x() + ", " + land.y() + ", " + land.z());
                        p.sendMessage("§7반경: §f" + land.radius());
                        p.sendMessage("§7활성: " + (land.enabled() ? "§aON" : "§cOFF"));
                        return true;
                    }

                    // 아래부터는 KING만
                    if (service.getRank(p.getUniqueId()) != Rank.KING) {
                        p.sendMessage("§c영지 설정은 KING만 가능!");
                        return true;
                    }

                    if (sub.equals("set")) {
                        int radius = 30;
                        if (args.length >= 3) {
                            try { radius = Integer.parseInt(args[2]); }
                            catch (NumberFormatException e) { p.sendMessage("§c반경은 숫자!"); return true; }
                        }
                        var loc = p.getLocation();
                        service.upsertFamilyLand(
                                familyId,
                                loc.getWorld().getName(),
                                loc.getBlockX(),
                                loc.getBlockY(),
                                loc.getBlockZ(),
                                radius,
                                true
                        );
                        p.sendMessage("§a영지 설정 완료!");
                        p.sendMessage("§7중심: " + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ()
                                + " / 반경=" + radius + " / 활성=ON");
                        return true;
                    }

                    if (sub.equals("radius")) {
                        if (args.length < 3) { p.sendMessage("§c사용법: /f land radius <n>"); return true; }
                        int r;
                        try { r = Integer.parseInt(args[2]); }
                        catch (NumberFormatException e) { p.sendMessage("§c반경은 숫자!"); return true; }
                        service.setFamilyLandRadius(familyId, r);
                        p.sendMessage("§a영지 반경 변경 완료: " + r);
                        return true;
                    }

                    if (sub.equals("on") || sub.equals("off")) {
                        boolean enabled = sub.equals("on");
                        service.setFamilyLandEnabled(familyId, enabled);
                        p.sendMessage("§a영지 보호 " + (enabled ? "§aON" : "§cOFF") + " 처리 완료!");
                        return true;
                    }

                    p.sendMessage("§c사용법: /f land info | set | radius | on|off");
                }

                // ✅ 승급/강등 (지금은 feudal.admin만)
                case "promote", "demote" -> {
                    if (args.length < 2) { p.sendMessage("§c/f " + args[0] + " <닉>"); return true; }
                    if (!p.hasPermission("feudal.admin")) {
                        p.sendMessage("§c권한이 없어!");
                        return true;
                    }
                    Player t = Bukkit.getPlayerExact(args[1]);
                    if (t == null) { p.sendMessage("§c그 유저는 온라인이 아님"); return true; }

                    var myF = service.getFamilyIdOf(p.getUniqueId());
                    var taF = service.getFamilyIdOf(t.getUniqueId());
                    if (myF.isEmpty() || taF.isEmpty() || !myF.get().equals(taF.get())) {
                        p.sendMessage("§c같은 가문만 승급/강등 가능");
                        return true;
                    }

                    Rank cur = service.getRank(t.getUniqueId());
                    Rank next = args[0].equalsIgnoreCase("promote") ? Rank.promote(cur) : Rank.demote(cur);

                    if (cur == next) {
                        p.sendMessage("§e더 이상 " + (args[0].equalsIgnoreCase("promote") ? "승급" : "강등") + "할 수 없어!");
                        p.sendMessage("§7현재 계급: " + cur.name());
                        return true;
                    }

                    service.setMember(t.getUniqueId(), myF.get(), next);
                    p.sendMessage("§a" + t.getName() + " 계급: §f" + cur.name() + " §7-> §e" + next.name());
                    t.sendMessage("§e너의 계급이 변경됨: §f" + cur.name() + " §7-> §e" + next.name());
                }

                // ✅ 초대: KING만
                case "invite" -> {
                    if (args.length < 2) { p.sendMessage("§c사용법: /f invite <닉>"); return true; }

                    var myF = service.getFamilyIdOf(p.getUniqueId());
                    if (myF.isEmpty()) { p.sendMessage("§c가문이 있어야 초대할 수 있어!"); return true; }

                    Rank myRank = service.getRank(p.getUniqueId());
                    if (myRank != Rank.KING) {
                        p.sendMessage("§c초대는 KING만 가능!");
                        return true;
                    }

                    Player t = Bukkit.getPlayerExact(args[1]);
                    if (t == null) { p.sendMessage("§c그 유저는 온라인이 아님"); return true; }

                    if (service.getFamilyIdOf(t.getUniqueId()).isPresent()) {
                        p.sendMessage("§c그 유저는 이미 가문이 있어!");
                        return true;
                    }

                    int familyId = myF.get();
                    String familyName = service.getFamilyNameById(familyId);

                    invites.put(t.getUniqueId(),
                            new PendingInvite(familyId, System.currentTimeMillis() + INVITE_EXPIRE_MS, familyName));

                    p.sendMessage("§a초대 보냄: §e" + t.getName() + "§a -> 가문 §6" + familyName);
                    t.sendMessage("§e[가문 초대] §6" + familyName + "§e 가문에 초대받았어!");
                    t.sendMessage("§7수락: §f/f accept");
                    t.sendMessage("§7거절/무시: 5분 후 만료");
                }

                // ✅ 수락
                case "accept" -> {
                    PendingInvite inv = invites.get(p.getUniqueId());
                    if (inv == null) { p.sendMessage("§7받은 초대가 없어."); return true; }

                    long now = System.currentTimeMillis();
                    if (now > inv.expiresAtMs) {
                        invites.remove(p.getUniqueId());
                        p.sendMessage("§c초대가 만료됐어.");
                        return true;
                    }

                    service.setMember(p.getUniqueId(), inv.familyId, Rank.PEASANT);
                    service.setJob(p.getUniqueId(), Job.NONE);
                    service.setSerf(p.getUniqueId(), false);

                    invites.remove(p.getUniqueId());
                    p.sendMessage("§a가입 완료! 가문: §6" + inv.familyName + " §7(기본: PEASANT/NONE)");
                }

                // ✅ 농노 시스템(플레이어)
                case "serf" -> {
                    if (args.length < 2) {
                        p.sendMessage("§e/f serf me");
                        p.sendMessage("§e/f serf set <닉> on|off  (KING만)");
                        return true;
                    }

                    if (args[1].equalsIgnoreCase("me")) {
                        boolean serf = service.isSerf(p.getUniqueId());
                        p.sendMessage("§6[농노] §f상태: " + (serf ? "§cON(농노)" : "§aOFF(자유민)"));
                        return true;
                    }

                    if (args[1].equalsIgnoreCase("set")) {
                        if (args.length < 4) {
                            p.sendMessage("§c사용법: /f serf set <닉> on|off");
                            return true;
                        }

                        Rank myRank = service.getRank(p.getUniqueId());
                        if (myRank != Rank.KING) {
                            p.sendMessage("§c농노 지정/해제는 KING만 가능!");
                            return true;
                        }

                        Player t = Bukkit.getPlayerExact(args[2]);
                        if (t == null) { p.sendMessage("§c그 유저는 온라인이 아님"); return true; }

                        var myF = service.getFamilyIdOf(p.getUniqueId());
                        var taF = service.getFamilyIdOf(t.getUniqueId());
                        if (myF.isEmpty() || taF.isEmpty() || !myF.get().equals(taF.get())) {
                            p.sendMessage("§c같은 가문만 농노 지정 가능");
                            return true;
                        }

                        boolean on;
                        if (args[3].equalsIgnoreCase("on")) on = true;
                        else if (args[3].equalsIgnoreCase("off")) on = false;
                        else { p.sendMessage("§con|off 중 하나로!"); return true; }

                        service.setSerf(t.getUniqueId(), on);

                        if (on) {
                            service.setJob(t.getUniqueId(), Job.FARMER);
                            t.sendMessage("§c너는 농노가 되었어. 직업이 FARMER로 지정됨.");
                        } else {
                            t.sendMessage("§a너는 농노에서 해방되었어.");
                        }

                        p.sendMessage("§a처리 완료: " + t.getName() + " 농노=" + (on ? "ON" : "OFF"));
                        return true;
                    }

                    p.sendMessage("§c사용법: /f serf me 또는 /f serf set ...");
                }

                // ✅ 직업(플레이어)
                case "job" -> {
                    if (args.length < 2) {
                        p.sendMessage("§e/f job me");
                        p.sendMessage("§e/f job set <닉> <job>  (KING만)");
                        p.sendMessage("§7job 예시: NONE, FARMER, MINER, GUARD, MERCHANT, BUILDER");
                        return true;
                    }

                    if (args[1].equalsIgnoreCase("me")) {
                        Job j = service.getJob(p.getUniqueId());
                        p.sendMessage("§b내 직업: §f" + j.name());
                        return true;
                    }

                    if (args[1].equalsIgnoreCase("set")) {
                        if (args.length < 4) { p.sendMessage("§c사용법: /f job set <닉> <job>"); return true; }

                        Rank myRank = service.getRank(p.getUniqueId());
                        if (myRank != Rank.KING) { p.sendMessage("§c직업 부여는 KING만 가능!"); return true; }

                        Player t = Bukkit.getPlayerExact(args[2]);
                        if (t == null) { p.sendMessage("§c그 유저는 온라인이 아님"); return true; }

                        var myF = service.getFamilyIdOf(p.getUniqueId());
                        var taF = service.getFamilyIdOf(t.getUniqueId());
                        if (myF.isEmpty() || taF.isEmpty() || !myF.get().equals(taF.get())) {
                            p.sendMessage("§c같은 가문만 직업 부여 가능");
                            return true;
                        }

                        String jobStr = args[3].toUpperCase();
                        if (!Job.isValid(jobStr)) {
                            p.sendMessage("§c없는 직업이야: " + args[3]);
                            p.sendMessage("§7가능: NONE, FARMER, MINER, GUARD, MERCHANT, BUILDER, TAXPAYER");
                            return true;
                        }

                        Job newJob = Job.valueOf(jobStr);

                        // ✅ 농노 직업 제한
                        boolean targetSerf = service.isSerf(t.getUniqueId());
                        if (targetSerf) {
                            boolean allowed =
                                    (newJob == Job.FARMER) ||
                                            (newJob == Job.MINER)  ||
                                            (newJob == Job.GUARD);

                            if (!allowed) {
                                p.sendMessage("§c농노는 직업이 제한돼! (허용: FARMER, MINER, GUARD)");
                                return true;
                            }
                        }

                        service.setJob(t.getUniqueId(), newJob);

                        p.sendMessage("§a직업 설정 완료: §e" + t.getName() + "§a -> §f" + newJob.name());
                        t.sendMessage("§e너의 직업이 변경됨: §f" + newJob.name());
                        return true;
                    }

                    p.sendMessage("§c사용법: /f job me 또는 /f job set ...");
                }

                // ✅ 가문 금고 확인
                case "bank" -> {
                    var fidOpt = service.getFamilyIdOf(p.getUniqueId());
                    if (fidOpt.isEmpty()) {
                        p.sendMessage("§7너는 가문이 없어(평민).");
                        return true;
                    }
                    int familyId = fidOpt.get();
                    int bal = taxService.getBankBalance(familyId);
                    p.sendMessage("§6[가문 금고] §ffamilyId=" + familyId + " §e잔액: " + bal + " (에메랄드 기준)");
                }

                // ✅ 인출: KING만
                case "withdraw" -> {
                    if (args.length < 2) { p.sendMessage("§c사용법: /f withdraw <amount>"); return true; }

                    int amount;
                    try { amount = Integer.parseInt(args[1]); }
                    catch (NumberFormatException e) { p.sendMessage("§camount는 숫자!"); return true; }
                    if (amount <= 0) { p.sendMessage("§c1 이상만 가능"); return true; }

                    var fidOpt = service.getFamilyIdOf(p.getUniqueId());
                    if (fidOpt.isEmpty()) {
                        p.sendMessage("§7너는 가문이 없어(평민).");
                        return true;
                    }
                    int familyId = fidOpt.get();

                    Rank r = service.getRank(p.getUniqueId());
                    if (r != Rank.KING) {
                        p.sendMessage("§c인출은 KING만 가능!");
                        return true;
                    }

                    boolean ok = taxService.withdrawFromBank(familyId, amount);
                    if (!ok) {
                        int bal = taxService.getBankBalance(familyId);
                        p.sendMessage("§c금고 잔액이 부족해! 현재 잔액: " + bal);
                        return true;
                    }

                    p.getInventory().addItem(new ItemStack(Material.EMERALD, amount));
                    p.sendMessage("§a가문 금고에서 §e" + amount + "§a 에메랄드를 인출했어!");
                }

                // ✅ NPC 관련
                case "npc" -> {
                    if (args.length < 2) {
                        p.sendMessage("§e/f npc role <npcId> <role>");
                        p.sendMessage("§e/f npc tax <npcId> <familyId> <amount> <intervalSec>");
                        p.sendMessage("§e/f npc family <npcId> <familyId>    (KING만)");
                        p.sendMessage("§e/f npc serf <npcId> on|off         (KING만)");
                        p.sendMessage("§e/f npc job <npcId> <job>           (KING만)");
                        p.sendMessage("§e/f npc info <npcId>");
                        return true;
                    }

                    // 공통: npcId 파싱 헬퍼
                    java.util.function.Function<String, Integer> parseNpcId = (s) -> {
                        try { return Integer.parseInt(s); }
                        catch (NumberFormatException e) { return null; }
                    };

                    // ✅ /f npc info <npcId>
                    if (args[1].equalsIgnoreCase("info")) {
                        if (args.length < 3) { p.sendMessage("§c사용법: /f npc info <npcId>"); return true; }

                        int npcId;
                        try { npcId = Integer.parseInt(args[2]); }
                        catch (NumberFormatException e) { p.sendMessage("§c<npcId> 는 숫자!"); return true; }

                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) { p.sendMessage("§cNPC 없음. ID: " + npcId); return true; }

                        var fidOpt = service.getNpcFamilyId(npcId);
                        String familyStr;
                        if (fidOpt.isEmpty()) familyStr = "§7(무소속)";
                        else familyStr = "§efamilyId=" + fidOpt.get() + " §7/ 이름=§a" + service.getFamilyNameById(fidOpt.get());

                        boolean serf = service.isNpcSerf(npcId);
                        Job job = service.getNpcJob(npcId);

                        p.sendMessage("§6[NPC INFO] §f#" + npcId + " §7(" + npc.getName() + ")");
                        p.sendMessage("§b가문: §f" + familyStr);
                        p.sendMessage("§b농노: " + (serf ? "§cON" : "§aOFF"));
                        p.sendMessage("§b직업: §f" + job.name());
                        return true;
                    }

                    // /f npc role
                    if (args[1].equalsIgnoreCase("role")) {
                        if (args.length < 4) {
                            p.sendMessage("§c사용법: /f npc role <npcId> <role>");
                            p.sendMessage("§7role: TAX_COLLECTOR, FARMER, GUARD, MINER");
                            return true;
                        }

                        Integer npcId = parseNpcId.apply(args[2]);
                        if (npcId == null) { p.sendMessage("§c<npcId> 는 숫자!"); return true; }

                        NPCRole role;
                        try { role = NPCRole.valueOf(args[3].toUpperCase()); }
                        catch (IllegalArgumentException e) {
                            p.sendMessage("§c없는 역할: " + args[3]);
                            p.sendMessage("§7가능: TAX_COLLECTOR, FARMER, GUARD, MINER");
                            return true;
                        }

                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) { p.sendMessage("§cNPC 없음. ID: " + npcId); return true; }

                        FeudalNPCTrait trait = npc.getOrAddTrait(FeudalNPCTrait.class);
                        trait.setRole(role);
                        p.sendMessage("§aNPC #" + npcId + " 역할 설정 완료: §e" + role);
                        return true;
                    }

                    // /f npc tax
                    if (args[1].equalsIgnoreCase("tax")) {
                        if (args.length < 6) {
                            p.sendMessage("§c사용법: /f npc tax <npcId> <familyId> <amount> <intervalSec>");
                            return true;
                        }

                        int npcId, familyId, amount, intervalSec;
                        try {
                            npcId = Integer.parseInt(args[2]);
                            familyId = Integer.parseInt(args[3]);
                            amount = Integer.parseInt(args[4]);
                            intervalSec = Integer.parseInt(args[5]);
                        } catch (NumberFormatException e) {
                            p.sendMessage("§c숫자 자리(npcId/familyId/amount/intervalSec) 중에 숫자가 아닌 게 있어!");
                            return true;
                        }

                        if (amount <= 0) { p.sendMessage("§camount는 1 이상!"); return true; }
                        if (intervalSec < 5) { p.sendMessage("§cintervalSec는 최소 5초 이상 추천!"); return true; }

                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) { p.sendMessage("§cNPC 없음. ID: " + npcId); return true; }

                        FeudalNPCTrait trait = npc.getOrAddTrait(FeudalNPCTrait.class);

                        if (trait.getRole() != NPCRole.TAX_COLLECTOR) {
                            p.sendMessage("§e경고: 이 NPC 역할이 TAX_COLLECTOR가 아님. 그래도 세팅은 저장함.");
                            p.sendMessage("§7원하면 먼저: /f npc role " + npcId + " TAX_COLLECTOR");
                        }

                        trait.setFamilyId(familyId);
                        trait.setTaxAmount(amount);
                        trait.setIntervalMs(intervalSec * 1000L);

                        long now = System.currentTimeMillis();
                        trait.setNextCollectAtMs(now + trait.getIntervalMs());

                        p.sendMessage("§a징세관 세팅 완료!");
                        p.sendMessage("§7NPC #" + npcId + " / familyId=" + familyId
                                + " / amount=" + amount + " emerald / interval=" + intervalSec + "s");
                        return true;
                    }

                    // /f npc family
                    if (args[1].equalsIgnoreCase("family")) {
                        if (args.length < 4) { p.sendMessage("§c사용법: /f npc family <npcId> <familyId>"); return true; }

                        if (service.getRank(p.getUniqueId()) != Rank.KING) {
                            p.sendMessage("§cNPC 가문 지정은 KING만 가능!");
                            return true;
                        }

                        int npcId, familyId;
                        try {
                            npcId = Integer.parseInt(args[2]);
                            familyId = Integer.parseInt(args[3]);
                        } catch (NumberFormatException e) {
                            p.sendMessage("§c<npcId>/<familyId> 는 숫자!");
                            return true;
                        }

                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) { p.sendMessage("§cNPC 없음. ID: " + npcId); return true; }

                        service.setNpcMember(npcId, familyId);
                        p.sendMessage("§aNPC #" + npcId + " 가문 귀속 완료: familyId=" + familyId);
                        return true;
                    }

                    // /f npc serf
                    if (args[1].equalsIgnoreCase("serf")) {
                        if (args.length < 4) { p.sendMessage("§c사용법: /f npc serf <npcId> on|off"); return true; }

                        if (service.getRank(p.getUniqueId()) != Rank.KING) {
                            p.sendMessage("§cNPC 농노 지정은 KING만 가능!");
                            return true;
                        }

                        int npcId;
                        try { npcId = Integer.parseInt(args[2]); }
                        catch (NumberFormatException e) { p.sendMessage("§c<npcId> 는 숫자!"); return true; }

                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) { p.sendMessage("§cNPC 없음. ID: " + npcId); return true; }

                        if (service.getNpcFamilyId(npcId).isEmpty()) {
                            p.sendMessage("§c이 NPC는 가문 소속이 없어. 먼저 지정해!");
                            p.sendMessage("§7/f npc family " + npcId + " <familyId>");
                            return true;
                        }

                        boolean on;
                        if (args[3].equalsIgnoreCase("on")) on = true;
                        else if (args[3].equalsIgnoreCase("off")) on = false;
                        else { p.sendMessage("§con|off 중 하나로!"); return true; }

                        service.setNpcSerf(npcId, on);

                        if (on) {
                            service.setNpcJob(npcId, Job.FARMER);
                        }

                        p.sendMessage("§aNPC 농노 설정 완료: #" + npcId + " -> " + (on ? "§cON" : "§aOFF"));
                        return true;
                    }

                    // /f npc job
                    if (args[1].equalsIgnoreCase("job")) {
                        if (args.length < 4) { p.sendMessage("§c사용법: /f npc job <npcId> <job>"); return true; }

                        if (service.getRank(p.getUniqueId()) != Rank.KING) {
                            p.sendMessage("§cNPC 직업 설정은 KING만 가능!");
                            return true;
                        }

                        int npcId;
                        try { npcId = Integer.parseInt(args[2]); }
                        catch (NumberFormatException e) { p.sendMessage("§c<npcId> 는 숫자!"); return true; }

                        NPC npc = CitizensAPI.getNPCRegistry().getById(npcId);
                        if (npc == null) { p.sendMessage("§cNPC 없음. ID: " + npcId); return true; }

                        String jobStr = args[3].toUpperCase();
                        if (!Job.isValid(jobStr)) {
                            p.sendMessage("§c없는 직업이야: " + args[3]);
                            p.sendMessage("§7가능: NONE, FARMER, MINER, GUARD, MERCHANT, BUILDER, TAXPAYER");
                            return true;
                        }

                        Job newJob = Job.valueOf(jobStr);

                        boolean npcSerf = service.isNpcSerf(npcId);
                        if (npcSerf) {
                            boolean allowed =
                                    (newJob == Job.FARMER) ||
                                            (newJob == Job.MINER)  ||
                                            (newJob == Job.GUARD);
                            if (!allowed) {
                                p.sendMessage("§c농노 NPC는 직업이 제한돼! (허용: FARMER, MINER, GUARD)");
                                return true;
                            }
                        }

                        service.setNpcJob(npcId, newJob);
                        p.sendMessage("§aNPC #" + npcId + " 직업 설정 완료: " + newJob.name());
                        return true;
                    }

                    p.sendMessage("§c사용법: /f npc role|tax|family|serf|job|info ...");
                }

                default -> help(p);
            }
        } catch (SQLException e) {
            p.sendMessage("§cDB 오류: " + e.getMessage());
        } catch (Exception e) {
            p.sendMessage("§c오류: " + e.getMessage());
        }

        return true;
    }

    private void help(Player p) {
        p.sendMessage("§e/f create <가문이름>");
        p.sendMessage("§e/f info");
        p.sendMessage("§e/f fid");
        p.sendMessage("§e/f land info|set|radius|on|off");
        p.sendMessage("§e/f invite <닉> (KING만)");
        p.sendMessage("§e/f accept");
        p.sendMessage("§e/f serf me");
        p.sendMessage("§e/f serf set <닉> on|off (KING만)");
        p.sendMessage("§e/f job me");
        p.sendMessage("§e/f job set <닉> <job> (KING만)");
        p.sendMessage("§e/f bank");
        p.sendMessage("§e/f withdraw <amount> (KING만)");
        p.sendMessage("§e/f promote <닉>  (admin)");
        p.sendMessage("§e/f demote <닉>   (admin)");
        p.sendMessage("§e/f npc role <npcId> <role>");
        p.sendMessage("§e/f npc tax <npcId> <familyId> <amount> <intervalSec>");
        p.sendMessage("§e/f npc family <npcId> <familyId> (KING만)");
        p.sendMessage("§e/f npc serf <npcId> on|off (KING만)");
        p.sendMessage("§e/f npc job <npcId> <job> (KING만)");
        p.sendMessage("§e/f npc info <npcId>");
    }
}