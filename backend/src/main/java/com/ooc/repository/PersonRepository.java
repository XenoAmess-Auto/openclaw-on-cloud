package com.ooc.repository;

import com.ooc.entity.person.Person;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PersonRepository extends MongoRepository<Person, String> {
    
    /**
     * 根据名称查找人物
     */
    Optional<Person> findByName(String name);
    
    /**
     * 查找所有存活的人物
     */
    List<Person> findByIsAliveTrue();
    
    /**
     * 查找所有死亡的人物
     */
    List<Person> findByIsAliveFalse();
    
    /**
     * 根据性别查找
     */
    List<Person> findByGender(Person.Gender gender);
    
    /**
     * 查找拥有指定特质的人物
     */
    List<Person> findByTraits_Type(String traitType);
}
