package com.ooc.service;

import com.ooc.entity.person.Person;
import com.ooc.entity.person.Trait;
import com.ooc.repository.PersonRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonService {
    
    private final PersonRepository personRepository;
    
    /**
     * 创建新人物
     */
    public Person createPerson(String name, String displayName, Person.Gender gender, int age) {
        Person person = Person.builder()
                .name(name)
                .displayName(displayName != null ? displayName : name)
                .gender(gender)
                .age(age)
                .isAlive(true)
                .build();
        return personRepository.save(person);
    }
    
    /**
     * 获取所有人物
     */
    public List<Person> getAllPersons() {
        return personRepository.findAll();
    }
    
    /**
     * 获取所有存活人物
     */
    public List<Person> getAlivePersons() {
        return personRepository.findByIsAliveTrue();
    }
    
    /**
     * 根据ID获取人物
     */
    public Optional<Person> getPersonById(String id) {
        return personRepository.findById(id);
    }
    
    /**
     * 根据名称获取人物
     */
    public Optional<Person> getPersonByName(String name) {
        return personRepository.findByName(name);
    }
    
    /**
     * 更新人物
     */
    public Person updatePerson(Person person) {
        person.setUpdatedAt(Instant.now());
        return personRepository.save(person);
    }
    
    /**
     * 删除人物
     */
    public void deletePerson(String id) {
        personRepository.deleteById(id);
    }
    
    /**
     * 添加特质到人物
     */
    public Person addTrait(String personId, Trait trait) {
        Person person = getPersonById(personId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + personId));
        person.addTrait(trait);
        return personRepository.save(person);
    }
    
    /**
     * 从人物移除特质
     */
    public Person removeTrait(String personId, String traitId) {
        Person person = getPersonById(personId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + personId));
        person.removeTrait(traitId);
        return personRepository.save(person);
    }
    
    /**
     * 人物死亡
     */
    public Person die(String personId, String reason) {
        Person person = getPersonById(personId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + personId));
        person.die(reason);
        log.info("人物 {} 已死亡，原因: {}", person.getName(), reason);
        return personRepository.save(person);
    }
    
    /**
     * 设置怀孕状态
     */
    public Person setPregnant(String personId, String fatherId, String fatherName, int daysUntilBirth) {
        Person person = getPersonById(personId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + personId));
        
        if (person.getGender() != Person.Gender.FEMALE) {
            throw new IllegalStateException("只有女性角色可以怀孕");
        }
        
        person.setPregnant(fatherId, fatherName, daysUntilBirth);
        log.info("人物 {} 已设置怀孕状态，父亲: {}，预计 {} 天后分娩", 
                person.getName(), fatherName, daysUntilBirth);
        return personRepository.save(person);
    }
    
    /**
     * 创建孩子并更新父母的孩子列表
     * 这是一个事务性操作，确保所有更新要么全部成功要么全部失败
     * 
     * @param name 孩子名称
     * @param displayName 显示名称
     * @param gender 性别
     * @param age 年龄
     * @param fatherId 父亲ID（可为null）
     * @param motherId 母亲ID（可为null）
     * @return 创建的孩子对象
     */
    public Person createChild(String name, String displayName, Person.Gender gender, int age, 
                              String fatherId, String motherId) {
        // 创建孩子
        Person child = Person.builder()
                .name(name)
                .displayName(displayName != null ? displayName : name)
                .gender(gender)
                .age(age)
                .isAlive(true)
                .fatherId(fatherId)
                .motherId(motherId)
                .build();
        
        Person savedChild = personRepository.save(child);
        log.info("孩子 {} 已创建，ID: {}", savedChild.getName(), savedChild.getId());
        
        // 更新父亲的孩子列表
        if (fatherId != null) {
            personRepository.findById(fatherId).ifPresent(father -> {
                father.addChild(savedChild.getId());
                personRepository.save(father);
                log.info("已更新父亲 {} 的孩子列表，新增孩子: {}", father.getName(), savedChild.getName());
            });
        }
        
        // 更新母亲的孩子列表
        if (motherId != null) {
            personRepository.findById(motherId).ifPresent(mother -> {
                mother.addChild(savedChild.getId());
                personRepository.save(mother);
                log.info("已更新母亲 {} 的孩子列表，新增孩子: {}", mother.getName(), savedChild.getName());
            });
        }
        
        return savedChild;
    }
    
    /**
     * 为现有人物设置父母并更新父母的children列表
     * 用于在已有数据库中为人物补全父母信息
     * 
     * @param personId 孩子ID
     * @param fatherId 父亲ID（可为null）
     * @param motherId 母亲ID（可为null）
     * @return 更新后的孩子对象
     */
    public Person setParents(String personId, String fatherId, String motherId) {
        Person person = getPersonById(personId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + personId));
        
        // 更新孩子的父母引用
        person.setParents(fatherId, motherId);
        
        // 更新父亲的孩子列表
        if (fatherId != null) {
            personRepository.findById(fatherId).ifPresent(father -> {
                father.addChild(personId);
                personRepository.save(father);
            });
        }
        
        // 更新母亲的孩子列表
        if (motherId != null) {
            personRepository.findById(motherId).ifPresent(mother -> {
                mother.addChild(personId);
                personRepository.save(mother);
            });
        }
        
        return personRepository.save(person);
    }
    public void cleanupExpiredTraits(String personId) {
        Person person = getPersonById(personId)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在: " + personId));
        person.cleanupExpiredTraits();
        personRepository.save(person);
    }
}
