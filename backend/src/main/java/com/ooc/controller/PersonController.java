package com.ooc.controller;

import com.ooc.entity.person.Person;
import com.ooc.entity.person.PregnancyTrait;
import com.ooc.service.PersonService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/persons")
@RequiredArgsConstructor
public class PersonController {
    
    private final PersonService personService;
    
    /**
     * 获取所有人物
     */
    @GetMapping
    public ResponseEntity<List<Person>> getAllPersons() {
        return ResponseEntity.ok(personService.getAllPersons());
    }
    
    /**
     * 获取所有存活人物
     */
    @GetMapping("/alive")
    public ResponseEntity<List<Person>> getAlivePersons() {
        return ResponseEntity.ok(personService.getAlivePersons());
    }
    
    /**
     * 根据ID获取人物
     */
    @GetMapping("/{id}")
    public ResponseEntity<Person> getPersonById(@PathVariable String id) {
        return personService.getPersonById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
    
    /**
     * 创建人物
     */
    @PostMapping
    public ResponseEntity<Person> createPerson(@RequestBody CreatePersonRequest request) {
        Person person = personService.createPerson(
                request.name(),
                request.displayName(),
                request.gender(),
                request.age()
        );
        return ResponseEntity.ok(person);
    }
    
    /**
     * 创建孩子并更新父母的children列表
     * 这是专门用于生育/创建子代的端点
     */
    @PostMapping("/child")
    public ResponseEntity<Person> createChild(@RequestBody CreateChildRequest request) {
        Person child = personService.createChild(
                request.name(),
                request.displayName(),
                request.gender(),
                request.age(),
                request.fatherId(),
                request.motherId()
        );
        return ResponseEntity.ok(child);
    }
    
    /**
     * 为现有人物设置父母（补全家谱信息）
     */
    @PostMapping("/{id}/parents")
    public ResponseEntity<Person> setParents(
            @PathVariable String id,
            @RequestBody SetParentsRequest request) {
        Person person = personService.setParents(id, request.fatherId(), request.motherId());
        return ResponseEntity.ok(person);
    }
    
    /**
     * 更新人物
     */
    @PutMapping("/{id}")
    public ResponseEntity<Person> updatePerson(@PathVariable String id, @RequestBody Person person) {
        person.setId(id);
        return ResponseEntity.ok(personService.updatePerson(person));
    }
    
    /**
     * 删除人物
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePerson(@PathVariable String id) {
        personService.deletePerson(id);
        return ResponseEntity.ok().build();
    }
    
    /**
     * 设置人物死亡
     */
    @PostMapping("/{id}/die")
    public ResponseEntity<Person> die(
            @PathVariable String id,
            @RequestBody Map<String, String> request) {
        String reason = request.getOrDefault("reason", "未知原因");
        return ResponseEntity.ok(personService.die(id, reason));
    }
    
    /**
     * 设置怀孕状态
     */
    @PostMapping("/{id}/pregnant")
    public ResponseEntity<? extends Object> setPregnant(
            @PathVariable String id,
            @RequestBody PregnancyRequest request) {
        try {
            Person person = personService.setPregnant(
                    id,
                    request.fatherId(),
                    request.fatherName(),
                    request.daysUntilBirth()
            );
            return ResponseEntity.ok(person);
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
    
    /**
     * 移除怀孕状态（流产或分娩）
     */
    @DeleteMapping("/{id}/pregnant")
    public ResponseEntity<Person> removePregnancy(@PathVariable String id) {
        Person person = personService.getPersonById(id)
                .orElseThrow(() -> new IllegalArgumentException("人物不存在"));
        person.removePregnancy();
        return ResponseEntity.ok(personService.updatePerson(person));
    }
    
    /**
     * 清理过期特质
     */
    @PostMapping("/{id}/cleanup-traits")
    public ResponseEntity<Void> cleanupTraits(@PathVariable String id) {
        personService.cleanupExpiredTraits(id);
        return ResponseEntity.ok().build();
    }
    
    // 请求DTO
    public record CreatePersonRequest(
            String name,
            String displayName,
            Person.Gender gender,
            int age
    ) {}
    
    public record CreateChildRequest(
            String name,
            String displayName,
            Person.Gender gender,
            int age,
            String fatherId,
            String motherId
    ) {}
    
    public record SetParentsRequest(
            String fatherId,
            String motherId
    ) {}
    
    public record PregnancyRequest(
            String fatherId,
            String fatherName,
            int daysUntilBirth
    ) {}
}
