package com.ooc.entity.person;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 怀孕特质
 * 表示人物处于怀孕状态
 * - 死亡时清除（不会保留到下一世）
 * - 有过期时间（分娩日期）
 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class PregnancyTrait extends AbstractTrait {
    
    public static final String TYPE = "PREGNANCY";
    
    /**
     * 怀孕阶段：早期(1-3月)、中期(4-6月)、晚期(7-9月)
     */
    public enum PregnancyStage {
        EARLY,    // 早期
        MIDDLE,   // 中期
        LATE      // 晚期
    }
    
    /**
     * 父亲ID（可能未知）
     */
    private String fatherId;
    
    /**
     * 父亲名称
     */
    private String fatherName;
    
    /**
     * 怀孕开始时间
     */
    private Instant conceptionDate;
    
    /**
     * 预计分娩时间
     */
    private Instant dueDate;
    
    /**
     * 当前阶段
     */
    private PregnancyStage stage;
    
    public PregnancyTrait(String personId, String fatherId, String fatherName) {
        setId(personId + "_pregnancy_" + System.currentTimeMillis());
        setType(TYPE);
        setName("怀孕");
        setDescription("处于怀孕状态，预计将在未来分娩");
        setCreatedAt(Instant.now());
        setConceptionDate(Instant.now());
        
        // 默认9个月后分娩
        setDueDate(Instant.now().plus(270, ChronoUnit.DAYS));
        setStage(PregnancyStage.EARLY);
        
        this.fatherId = fatherId;
        this.fatherName = fatherName;
    }
    
    /**
     * 根据指定天数创建怀孕状态
     */
    public static PregnancyTrait create(String personId, String fatherId, String fatherName, int daysUntilBirth) {
        PregnancyTrait trait = new PregnancyTrait();
        trait.setId(personId + "_pregnancy_" + System.currentTimeMillis());
        trait.setType(TYPE);
        trait.setName("怀孕");
        trait.setDescription("处于怀孕状态，预计将在" + daysUntilBirth + "天后分娩");
        trait.setCreatedAt(Instant.now());
        trait.setConceptionDate(Instant.now());
        trait.setDueDate(Instant.now().plus(daysUntilBirth, ChronoUnit.DAYS));
        trait.setStage(calculateStage(daysUntilBirth));
        trait.setFatherId(fatherId);
        trait.setFatherName(fatherName);
        trait.setExpiresAt(trait.getDueDate());
        return trait;
    }
    
    /**
     * 根据剩余天数计算阶段
     */
    private static PregnancyStage calculateStage(int daysUntilBirth) {
        if (daysUntilBirth > 180) {
            return PregnancyStage.EARLY;
        } else if (daysUntilBirth > 90) {
            return PregnancyStage.MIDDLE;
        } else {
            return PregnancyStage.LATE;
        }
    }
    
    @Override
    public boolean isClearedOnDeath() {
        return true; // 怀孕状态在死亡时清除
    }
    
    /**
     * 获取距离分娩还有多少天
     */
    public long getDaysUntilDue() {
        if (dueDate == null) return 0;
        return ChronoUnit.DAYS.between(Instant.now(), dueDate);
    }
    
    /**
     * 获取阶段显示名称
     */
    public String getStageDisplayName() {
        return switch (stage) {
            case EARLY -> "孕早期";
            case MIDDLE -> "孕中期";
            case LATE -> "孕晚期";
        };
    }
    
    /**
     * 更新怀孕阶段
     */
    public void updateStage() {
        long daysUntil = getDaysUntilDue();
        this.stage = calculateStage((int) daysUntil);
    }
}
