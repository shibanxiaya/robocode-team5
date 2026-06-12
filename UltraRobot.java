package myrobots;

import robocode.*;
import robocode.util.Utils;
import java.awt.Color;
import java.awt.geom.Point2D;
import java.util.*;

/**
 * ╔══════════════════════════════════════════════════════╗
 * ║          UltraRobot v9  —— myrobots 终极战士         ║
 * ╠══════════════════════════════════════════════════════╣
 * ║  瞄准：圆周预测 + 转弯变化量 + GF 统计偏移修正       ║
 * ║  走位：MRM + 子弹波排斥 + 大地图长距离移动           ║
 * ║  雷达：onScannedRobot 内即时收紧 + 主循环兜底全扫    ║
 * ║  策略：分段火力（扩展远距）+ 放宽开火 + 近距击杀     ║
 * ╚══════════════════════════════════════════════════════╝
 *
 * v9 改动（1200×1200 大地图优化）：
 * 1. 移动候选距离扩大：80/150/220/290 → 120/240/360/480
 * 2. 火力分段扩展到 800+ 距离，远距不再只打 1.2
 * 3. 开火阈值 6° → 10°，提高开火频率
 * 4. 目标选择改为纯最近距离（抄 Whyrobot），混战乱战效率更高
 * 5. 子弹波排斥系数微调，避免过度躲避导致站桩
 */
public class UltraRobot extends AdvancedRobot {

    // ===================== GF 统计桶 =====================
    private static final int GF_SLICES = 31;
    private static final int GF_MID    = GF_SLICES / 2;
    private static final double VEL_THRESHOLD = 4.0;

    // ===================== 敌人数据 =====================
    private final Map<String, EnemyInfo> enemies = new HashMap<>();
    private EnemyInfo target = null;

    // ===================== 子弹波（来弹 + 我弹）=====================
    private final List<BulletWave> waves = new ArrayList<>();

    // ===================== GF 统计表 =====================
    private final Map<String, int[][]> gfStats = new HashMap<>();

    // ===================== 场地尺寸 =====================
    private double fieldW, fieldH;

    // ===================== 颜色 =====================
    private static final Color C_BODY   = new Color(15, 15, 40);
    private static final Color C_GUN    = new Color(255, 80, 0);
    private static final Color C_RADAR  = new Color(0, 220, 255);
    private static final Color C_BULLET = new Color(255, 220, 50);

    // ===================== 移动候选距离（大地图）=====================
    private static final double[] MOVE_DISTANCES = {120, 240, 360, 480};

    // =======================================================
    //  主循环
    // =======================================================
    @Override
    public void run() {
        fieldW = getBattleFieldWidth();
        fieldH = getBattleFieldHeight();

        setBodyColor(C_BODY);
        setGunColor(C_GUN);
        setRadarColor(C_RADAR);
        setBulletColor(C_BULLET);

        setAdjustGunForRobotTurn(true);
        setAdjustRadarForGunTurn(true);
        setAdjustRadarForRobotTurn(true);

        setTurnRadarRight(Double.MAX_VALUE);

        while (true) {
            if (target == null || getTime() - target.lastScanTime > 8) {
                setTurnRadarRightRadians(Double.POSITIVE_INFINITY);
            }

            cleanupEnemies();
            updateTarget();
            doMovement();
            doGun();
            tickWaves();

            if (getTime() % 400 == 0 && getTime() > 0) {
                decayGFStats();
            }
            execute();
        }
    }

    private boolean isOneOnOne() {
        return getOthers() <= 1;
    }

    // =======================================================
    //  瞄准系统
    // =======================================================
    private void doGun() {
        if (target == null) return;

        double dist = target.distance;
        double bp = chooseBulletPower(dist, getEnergy(), target.energy);
        if (bp <= 0) return;

        double speed = 20 - 3 * bp;

        double[] circPred = circularPredict(target, speed);
        double aimAngle = Math.atan2(circPred[0] - getX(), circPred[1] - getY());

        int[][] statsArr = gfStats.computeIfAbsent(target.name, k -> new int[2][GF_SLICES]);
        int modeIdx = Math.abs(target.velocity) >= VEL_THRESHOLD ? 0 : 1;
        int[] stats = smoothStats(statsArr[modeIdx]);
        if (hasData(stats)) {
            int gfBest = bestGFSlice(stats);
            double gfOff = gfIndexToAngle(gfBest, dist, bp) * target.orbDir;
            aimAngle += gfOff;
        }

        double gunTurn = Utils.normalRelativeAngle(aimAngle - getGunHeadingRadians());
        setTurnGunRightRadians(gunTurn);

        // v9: 放宽到 10°，提高开火频率（大地图远距离精度下降）
        if (getGunHeat() == 0 && Math.abs(gunTurn) < Math.toRadians(10) && bp > 0) {
            Bullet b = setFireBullet(bp);
            if (b != null) {
                MyBulletWave mbw = new MyBulletWave();
                mbw.startX     = getX();
                mbw.startY     = getY();
                mbw.fireTime   = getTime();
                mbw.speed      = speed;
                mbw.power      = bp;
                mbw.aimAngle   = getGunHeadingRadians();
                mbw.targetName = target.name;
                mbw.targetX    = target.x;
                mbw.targetY    = target.y;
                mbw.targetVel  = target.velocity;
                mbw.targetHRad = target.headingRad;
                mbw.orbDir     = target.orbDir;
                mbw.velAtFire  = Math.abs(target.velocity);
                mbw.bullet     = b;
                mbw.isEnemy    = false;
                waves.add(mbw);
            }
        }
    }

    private double[] circularPredict(EnemyInfo e, double bSpeed) {
        double ex = e.x, ey = e.y;
        double hRad = e.headingRad;
        double vel  = e.velocity;
        double dH   = e.dHeadingRad;
        double mx = getX(), my = getY();

        for (int t = 0; t < 90; t++) {
            hRad += dH;
            ex += Math.sin(hRad) * vel;
            ey += Math.cos(hRad) * vel;
            ex = clamp(ex, 18, fieldW - 18);
            ey = clamp(ey, 18, fieldH - 18);
            if (Point2D.distance(mx, my, ex, ey) / bSpeed <= t + 1) break;
        }
        return new double[]{ex, ey};
    }

    // =======================================================
    //  移动系统：MRM + 子弹波排斥 + 大地图长距离
    // =======================================================
    private void doMovement() {
        if (enemies.isEmpty()) {
            wanderSafely();
            return;
        }

        double bestRisk = Double.POSITIVE_INFINITY;
        double bestX = getX(), bestY = getY();

        // v9: 36方向 × 4长距离段 = 144候选点
        for (double a = 0; a < Math.PI * 2; a += Math.PI / 18) {
            for (double dist : MOVE_DISTANCES) {
                double tx = getX() + Math.sin(a) * dist;
                double ty = getY() + Math.cos(a) * dist;
                tx = clamp(tx, 40, fieldW - 40);
                ty = clamp(ty, 40, fieldH - 40);
                double risk = evaluateRisk(tx, ty);
                if (risk < bestRisk) {
                    bestRisk = risk;
                    bestX = tx;
                    bestY = ty;
                }
            }
        }

        goTo(bestX, bestY);
    }

    private double evaluateRisk(double px, double py) {
        double risk = 0;

        // 墙角指数风险（距墙 <40px 开始陡增）
        risk += Math.exp(Math.max(0, 40 - px) / 15.0);
        risk += Math.exp(Math.max(0, 40 - py) / 15.0);
        risk += Math.exp(Math.max(0, px - (fieldW - 40)) / 15.0);
        risk += Math.exp(Math.max(0, py - (fieldH - 40)) / 15.0);

        // 来弹波排斥（系数微调，避免过度躲避）
        for (BulletWave w : waves) {
            if (!w.isEnemy) continue;
            long age = getTime() - w.fireTime;
            if (age <= 0 || age > 120) continue;
            double traveled = age * w.speed;
            double distToWave = Point2D.distance(px, py, w.startX, w.startY);
            double waveGap = Math.abs(distToWave - traveled);
            if (waveGap < 50) {
                risk += 4000.0 / (waveGap + 1);
            } else if (waveGap < 150) {
                risk += 60.0 / (waveGap + 1);
            }
        }

        // 敌人距离风险
        for (EnemyInfo en : enemies.values()) {
            double d = Point2D.distance(px, py, en.x, en.y);
            double weight = (en == target) ? 2.0 : 1.0;
            if (d < 150) risk += weight * (150 - d) * 3.0;
            else if (d > 600) risk += (d - 600) * 0.6;
            else risk += weight * (100000.0 / Math.max(d * d, 1));
        }

        return risk;
    }

    private void wanderSafely() {
        double cx = fieldW / 2, cy = fieldH / 2;
        double dx = cx - getX(), dy = cy - getY();
        if (Math.hypot(dx, dy) > 200) {
            double angle = Math.atan2(dx, dy);
            setTurnRightRadians(Utils.normalRelativeAngle(angle - getHeadingRadians()));
            setAhead(150);
        } else {
            setTurnRight(30);
            setAhead(150);
        }
    }

    private void goTo(double tx, double ty) {
        double dx = tx - getX(), dy = ty - getY();
        double dist = Math.hypot(dx, dy);
        if (dist < 15) return;
        double angle = Math.atan2(dx, dy);
        double turn = Utils.normalRelativeAngle(angle - getHeadingRadians());
        turn = smoothWallAngle(turn, 120);
        if (Math.abs(turn) > Math.PI / 2) {
            setTurnRightRadians(Utils.normalRelativeAngle(turn + Math.PI));
            setAhead(-dist);
        } else {
            setTurnRightRadians(turn);
            setAhead(dist);
        }
    }

    private double smoothWallAngle(double relRad, double margin) {
        for (int i = 0; i < 36; i++) {
            double testX = getX() + Math.sin(relRad) * margin;
            double testY = getY() + Math.cos(relRad) * margin;
            if (testX >= 30 && testX <= fieldW - 30 && testY >= 30 && testY <= fieldH - 30) break;
            relRad = (i % 2 == 0)
                ? Utils.normalRelativeAngle(relRad - Math.toRadians((i / 2 + 1) * 10))
                : Utils.normalRelativeAngle(relRad + Math.toRadians(((i + 1) / 2) * 10));
        }
        return relRad;
    }

    private void tickWaves() {
        long now = getTime();
        double myX = getX(), myY = getY();
        waves.removeIf(w -> {
            long age = now - w.fireTime;
            double traveled = age * w.speed;
            double myDist = Point2D.distance(myX, myY, w.startX, w.startY);
            return traveled > myDist + 120 || age > 220;
        });
    }

    // =======================================================
    //  事件处理
    // =======================================================
    @Override
    public void onScannedRobot(ScannedRobotEvent e) {
        String name = e.getName();
        EnemyInfo d = enemies.get(name);
        if (d == null) { d = new EnemyInfo(); d.name = name; enemies.put(name, d); }

        double absB = getHeadingRadians() + e.getBearingRadians();
        double nx = getX() + e.getDistance() * Math.sin(absB);
        double ny = getY() + e.getDistance() * Math.cos(absB);
        double newHRad = e.getHeadingRadians();

        if (d.lastScanTime > 0 && getTime() - d.lastScanTime == 1) {
            d.dHeadingRad = Utils.normalRelativeAngle(newHRad - d.headingRad);
        } else {
            d.dHeadingRad = 0;
        }

        if (d.lastScanTime == getTime() - 1 && e.getEnergy() < d.energy - 0.09) {
            double energyDrop = d.energy - e.getEnergy();
            double estimatedPower = clamp(energyDrop, 0.1, 3.0);
            double estimatedSpeed = 20 - 3 * estimatedPower;
            EnemyWave ew = new EnemyWave();
            ew.startX  = d.x;
            ew.startY  = d.y;
            ew.fireTime = getTime();
            ew.speed   = estimatedSpeed;
            ew.isEnemy = true;
            ew.angleToUs = Math.atan2(getX() - d.x, getY() - d.y);
            waves.add(ew);
        }

        double lateralVel = e.getVelocity() * Math.sin(e.getBearingRadians() + Math.PI);
        if (Math.abs(lateralVel) >= 0.5) d.orbDir = lateralVel >= 0 ? 1 : -1;

        d.bearingRad = e.getBearingRadians();
        d.distance   = e.getDistance();
        d.headingRad = newHRad;
        d.velocity   = e.getVelocity();
        d.energy     = e.getEnergy();
        d.x = nx; d.y = ny;
        d.lastScanTime = getTime();

        gfStats.computeIfAbsent(name, k -> new int[2][GF_SLICES]);

        double rt = Utils.normalRelativeAngle(absB - getRadarHeadingRadians());
        setTurnRadarRightRadians(rt > 0 ? Math.PI * 0.45 : -Math.PI * 0.45);
    }

    @Override
    public void onBulletHit(BulletHitEvent e) {
        for (BulletWave w : waves) {
            if (!(w instanceof MyBulletWave)) continue;
            MyBulletWave mw = (MyBulletWave) w;
            if (mw.bullet == e.getBullet()) {
                recordGFHit(mw, e.getName());
                break;
            }
        }
    }

    private void recordGFHit(MyBulletWave mw, String hitName) {
        int[][] statsArr = gfStats.computeIfAbsent(hitName, k -> new int[2][GF_SLICES]);
        int modeIdx = mw.velAtFire >= VEL_THRESHOLD ? 0 : 1;
        int[] stats = statsArr[modeIdx];
        EnemyInfo en = enemies.get(hitName);
        if (en == null) return;
        double actualAngle = Math.atan2(en.x - mw.startX, en.y - mw.startY);
        double deltaAngle  = Utils.normalRelativeAngle(actualAngle - mw.aimAngle);
        double maxAngle    = maxEscapeAngle(mw.speed);
        double gf = maxAngle > 0 ? deltaAngle / maxAngle : 0;
        int slice = (int) Math.round(gf * GF_MID * mw.orbDir) + GF_MID;
        slice = Math.max(0, Math.min(GF_SLICES - 1, slice));
        stats[slice] += 3;
        if (slice > 0) stats[slice - 1] += 1;
        if (slice < GF_SLICES - 1) stats[slice + 1] += 1;
    }

    @Override
    public void onHitByBullet(HitByBulletEvent e) {
        waves.removeIf(w -> w.isEnemy);
    }

    @Override
    public void onHitWall(HitWallEvent e) {
        setBack(50);
    }

    @Override
    public void onHitRobot(HitRobotEvent e) {
        if (e.getEnergy() < 8) {
            setFire(3);
        } else {
            setBack(40);
        }
    }

    @Override
    public void onRobotDeath(RobotDeathEvent e) {
        enemies.remove(e.getName());
        if (target != null && target.name.equals(e.getName())) target = null;
    }

    @Override
    public void onBulletMissed(BulletMissedEvent e) {}

    // =======================================================
    //  目标选择：v9 改为纯最近距离（抄 Whyrobot，混战乱战更高效）
    // =======================================================
    private void updateTarget() {
        target = null;
        double closestDist = Double.MAX_VALUE;
        for (EnemyInfo en : enemies.values()) {
            if (en.distance < closestDist) {
                closestDist = en.distance;
                target = en;
            }
        }
    }

    private void cleanupEnemies() {
        long now = getTime();
        enemies.entrySet().removeIf(e -> now - e.getValue().lastScanTime > 40);
    }

    // =======================================================
    //  火力选择：v9 扩展到 800+ 距离
    // =======================================================
    private double chooseBulletPower(double dist, double myE, double eneE) {
        if (myE < 5)  return 0.1;
        if (eneE < 4) return Math.min(eneE + 0.1, 3.0);

        double base;
        // v9: 扩展到 800+ 距离，大地图远距也能打出伤害
        if (myE > 50) {
            if (dist < 200)       base = 3.0;
            else if (dist < 400)  base = 2.5;
            else if (dist < 600)  base = 2.0;
            else if (dist < 800)  base = 1.5;
            else                  base = 1.0;
        } else if (myE > 30) {
            if (dist < 250)       base = 2.5;
            else if (dist < 450)  base = 2.0;
            else if (dist < 700)  base = 1.5;
            else                  base = 0.8;
        } else if (myE > 15) {
            if (dist < 300)       base = 2.0;
            else if (dist < 550)  base = 1.5;
            else if (dist < 800)  base = 1.0;
            else                  base = 0.5;
        } else {
            if (dist < 300)       base = 1.5;
            else if (dist < 500)  base = 1.0;
            else                  base = 0.4;
        }

        if (isOneOnOne() && base < 1.5) base = 1.5;
        base = Math.min(base, myE / 4.0);
        base = Math.max(base, 0.1);
        return base;
    }

    // =======================================================
    //  GF 辅助
    // =======================================================
    private int bestGFSlice(int[] stats) {
        int best = GF_MID, max = -1;
        for (int i = 0; i < GF_SLICES; i++) {
            if (stats[i] > max) { max = stats[i]; best = i; }
        }
        return best;
    }

    private int[] smoothStats(int[] raw) {
        int[] s = new int[GF_SLICES];
        for (int i = 0; i < GF_SLICES; i++) {
            s[i] = (int)(raw[i] * 0.6
                + raw[Math.max(0, i - 1)] * 0.2
                + raw[Math.min(GF_SLICES - 1, i + 1)] * 0.2);
        }
        return s;
    }

    private boolean hasData(int[] stats) {
        int total = 0;
        for (int s : stats) total += s;
        return total > 3;
    }

    private double gfIndexToAngle(int idx, double dist, double bp) {
        double mea = maxEscapeAngle(20 - 3 * bp);
        return mea * (idx - GF_MID) / GF_MID;
    }

    private double maxEscapeAngle(double bSpeed) {
        return Math.asin(Math.min(1, 8.0 / bSpeed));
    }

    private void decayGFStats() {
        for (int[][] arr : gfStats.values()) {
            for (int[] bucket : arr) {
                for (int i = 0; i < bucket.length; i++) {
                    bucket[i] = (int)(bucket[i] * 0.7);
                }
            }
        }
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // =======================================================
    //  内部类
    // =======================================================
    static class EnemyInfo {
        String name;
        double bearingRad, distance, headingRad, dHeadingRad;
        double velocity, energy, x, y;
        int orbDir = 1;
        long lastScanTime = 0;
    }

    static class BulletWave {
        double startX, startY, speed;
        long fireTime;
        boolean isEnemy;
    }

    static class EnemyWave extends BulletWave {
        double angleToUs;
    }

    static class MyBulletWave extends BulletWave {
        double power, aimAngle, targetX, targetY, targetVel, targetHRad;
        double velAtFire;
        int orbDir;
        String targetName;
        Bullet bullet;
    }
}
