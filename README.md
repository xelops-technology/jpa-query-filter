# Introduction
JPA Query Filter est une extension JPA qui simplifie considérablement la construction des requêtes de recherche multi-critères en ajoutant une couche d'abstraction intuitive sous le Criteria API.
# Installation
### 1 - Requirements
- Java 17+
- Spring Boot 3.0.0+
### 2 - Dependence
```
<dependency>
    <groupId>ma.xelops</groupId>
    <artifactId>jpa-query-filter</artifactId>
    <version>1.0.0</version>
</dependency>
```
### 2 - Existant
L'API Criteria est une API permettant de générer des requêtes avec des objets Java™ , comme alternative à la génération de chaînes pour les requêtes JPQL (Java Persistence Query Language). Néanmoins l'API requis beaucoup de boilerplate et de répétition pour les cas courants.

JPA Query Filter vient justement pour exposer une API plus abstraite qui gère le boilerplate de manière dynamique, facilitant ainsi l'utilisation et la création des repositories de recherche multi-critères.
# Ressources
[Medium overview](link-to-medium)
