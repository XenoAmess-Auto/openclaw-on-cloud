package com.ooc.entity.person;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 人物实体
 * 代表游戏/模拟中的一个角色
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "persons")
public class Person {
    
    @JsonProperty("id")
    @Id
    private String id;
    
    @JsonProperty("name")
    private String name;
    
    @JsonProperty("displayName")
    private String displayName;
    
    @JsonProperty("avatar")
    private String avatar;
    
    @JsonProperty("gender")
    private Gender gender;
    
    @JsonProperty("age")
    private int age;
    
    @JsonProperty("isAlive")
    @Builder.Default
    private boolean isAlive = true;
    
    @JsonProperty("deathTime")
    private Instant deathTime;
    
    @JsonProperty("deathReason")
    private String deathReason;
    
    /**
     * 人物的所有特质/状态
     */
    @JsonProperty("traits")
    @Builder.Default
    private List<Trait> traits = new ArrayList<>();
    
    @JsonProperty("createdAt")
    @CreatedDate
    private Instant createdAt;
    
    @JsonProperty("updatedAt")
    @LastModifiedDate
    private Instant updatedAt;
    
    /**
     * 性别枚举
     */
    public enum Gender {
        MALE,      // 男性
        FEMALE,    // 女性
        UNKNOWN    // 未知
    }
    
    /**
     * 添加特质
     */
    public void addTrait(Trait trait) {
        if (traits == null) {
            traits = new ArrayList<>();
        }
        // 移除同类型的旧trait（如果存在）
        traits.removeIf(t -> t.getType().equals(trait.getType()));
        traits.add(trait);
    }
    
    /**
     * 移除特质
     */
    public void removeTrait(String traitId) {
        if (traits != null) {
            traits.removeIf(t -> t.getId().equals(traitId));
        }
    }
    
    /**
     * 根据类型移除特质
     */
    public void removeTraitByType(String type) {
        if (traits != null) {
            traits.removeIf(t -> t.getType().equals(type));
        }
    }
    
    /**
     * 获取指定类型的特质
     */
    public Optional<Trait> getTraitByType(String type) {
        if (traits == null) return Optional.empty();
        return traits.stream()
                .filter(t -> t.getType().equals(type))
                .findFirst();
    }
    
    /**
     * 判断是否拥有指定类型的特质
     */
    public boolean hasTrait(String type) {
        return getTraitByType(type).isPresent();
    }
    
    /**
     * 获取所有未过期的特质
     */
    public List<Trait> getActiveTraits() {
        if (traits == null) return new ArrayList<>();
        return traits.stream()
                .filter(t -> !t.isExpired())
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有在死亡时会清除的特质
     */
    public List<Trait> getClearedOnDeathTraits() {
        if (traits == null) return new ArrayList<>();
        return traits.stream()
                .filter(Trait::isClearedOnDeath)
                .collect(Collectors.toList());
    }
    
    /**
     * 获取所有在死亡后会保留的特质
     */
    public List<Trait> getPersistentTraits() {
        if (traits == null) return new ArrayList<>();
        return traits.stream()
                .filter(t -> !t.isClearedOnDeath())
                .collect(Collectors.toList());
    }
    
    /**
     * 人物死亡处理
     * - 清除会在死亡时清除的特质
     * - 保留其他特质
     */
    public void die(String reason) {
        if (!isAlive) return;
        
        isAlive = false;
        deathTime = Instant.now();
        deathReason = reason;
        
        // 清除在死亡时会清除的特质
        if (traits != null) {
            traits.removeIf(Trait::isClearedOnDeath);
        }
    }
    
    /**
     * 复活（如果需要的话）
     */
    public void revive() {
        isAlive = true;
        deathTime = null;
        deathReason = null;
    }
    
    /**
     * 清理已过期的特质
     */
    public void cleanupExpiredTraits() {
        if (traits != null) {
            traits.removeIf(Trait::isExpired);
        }
    }
    
    /**
     * 判断当前是否怀孕
     */
    public boolean isPregnant() {
        return hasTrait(PregnancyTrait.TYPE);
    }
    
    /**
     * 获取怀孕状态详情
     */
    public Optional<PregnancyTrait> getPregnancy() {
        return getTraitByType(PregnancyTrait.TYPE)
                .filter(t -> t instanceof PregnancyTrait)
                .map(t -> (PregnancyTrait) t);
    }
    
    /**
     * 设置怀孕状态
     */
    public void setPregnant(String fatherId, String fatherName, int daysUntilBirth) {
        if (gender != Gender.FEMALE) {
            throw new IllegalStateException("只有女性角色可以怀孕");
        }
        PregnancyTrait pregnancy = PregnancyTrait.create(this.id, fatherId, fatherName, daysUntilBirth);
        addTrait(pregnancy);
    }
    
    /**
     * 移除怀孕状态（流产或分娩后）
     */
    public void removePregnancy() {
        removeTraitByType(PregnancyTrait.TYPE);
    }
}
