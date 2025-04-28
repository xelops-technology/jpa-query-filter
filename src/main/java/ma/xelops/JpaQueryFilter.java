package ma.xelops;

import com.google.common.reflect.TypeToken;
import ma.xelops.annotations.JpaQueryIgnore;
import ma.xelops.annotations.JpaQueryPath;
import ma.xelops.annotations.JpaQueryFieldsOperations;
import ma.xelops.annotations.JpaQueryOperation;
import ma.xelops.enums.JpaQueryOperationEnum;
import ma.xelops.exceptions.InvalidFieldTypeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import jakarta.persistence.criteria.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.*;
import java.util.stream.Collectors;

/**
 * This is an extension to the Jpa repository interface, providing a new filtering method to the domain repository. <br><br>
 * How to use: <br>
 * - Extend the repository class with this interface and provide the according domain class <br>
 * - Create an implementation class of the {@link QueryFilterCriteria} interface, which will contain the Filter criteria <br>
 * - Add the criteria fields to the implemented class, the fields name by default is expected to be the same as the according domain equivalent and the default operation on the fields is equality <br>
 * - Annotate the filter criteria fields with the {@link JpaQueryOperation} to specify the desired operation to be applied on the field <br>
 * - Annotate the filter criteria fields with the {@link JpaQueryPath} to specify a make a custom mapping with the according domain field <br>
 * <br>
 * Note: <br>
 * {@link JpaQueryOperation} Should be coherent with the field Type. <br>
 * Examples: <br><br>
 * - Invalide use: <br>
 * {@code @JpaQueryOperation(IN) private int singleValueField;} <br><br>
 * - Invalide use: <br>
 * {@code @JpaQueryOperation(LIKE) private List<String> multiValueField;} <br><br>
 * - Valide use: <br>
 * {@code @JpaQueryOperation(LIKE) private String singleValueStringField;} <br><br>
 * - Valide use: <br>
 * {@code @JpaQueryOperation(IN) private List<String> multiValueField;} <br><br>
 * @param <T> Filtered domain type
 */
public interface JpaQueryFilter<T> extends JpaSpecificationExecutor<T> {
    Logger log = LoggerFactory.getLogger(JpaQueryFilter.class);

    @SuppressWarnings("unchecked")
    private Class<T> getType() {
        var typeToken = new TypeToken<T>(getClass()) {};
        return (Class<T>) typeToken.getRawType();
    }

    private <V extends Annotation> Optional<V> getFieldAnnotation(Field field, Class<V> annotationClass) {
        return Optional.ofNullable(field.getAnnotation(annotationClass));
    }

    private boolean isCollectionType(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type);
    }

    private Class<?> getFieldType(Field field) {
        Class<?> type;
        if (isCollectionType(field.getType())) {
            var genericType = (ParameterizedType) field.getGenericType();
            type = (Class<?>) genericType.getActualTypeArguments()[0];
        } else {
            type = field.getType();
        }

        return type;
    }

    private boolean isUserType(Field field) {
        if (field == null) {
            throw new NullPointerException("field is marked @NonNull but is null");
        } else {
            Class<?> type = getFieldType(field);
            String packageName = type.getPackage().getName();
            return isUserPackage(packageName);
        }
    }

    private boolean isUserPackage(String packageName) {
        return !packageName.startsWith("java.");
    }

    private List<Field> getRecursiveDeclaredFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        var baseClass = clazz;
        while (isUserPackage(baseClass.getPackageName())) {
            fields.addAll(List.of(baseClass.getDeclaredFields()));
//            if (List.of(baseClass.getInterfaces()).contains(QueryFilterCriteria.class)) {
//                break;
//            }

            baseClass = baseClass.getSuperclass();
        }

        fields.forEach(field -> field.setAccessible(true));
        return fields;
    }

    private Field getRecursiveDeclaredField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Field field = null;
        var baseClass = clazz;
        while (isUserPackage(baseClass.getPackageName())) {
            try {
                field = baseClass.getDeclaredField(fieldName);
                break;
            } catch (NoSuchFieldException e) {
                baseClass = baseClass.getSuperclass();
            }
        }

        if (field == null) {
            throw new NoSuchFieldException();
        }

        return field;
    }

    private boolean isJpaAnnotation(Annotation annotation) {
        if (annotation == null) {
            throw new NullPointerException("annotation is marked @NonNull but is null");
        } else {
            String packageName = annotation.annotationType().getPackage().getName();
            return packageName.startsWith("jakarta.persistence");
        }
    }

    private boolean isNotDomainColumn(Field entityField) {
        return isUserType(entityField) || entityField.getType().isEnum() || Arrays.stream(entityField.getAnnotations())
                .noneMatch(this::isJpaAnnotation);
    }

    private Join<?, ?> buildPathJoin(Root<T> root, Set<Join<?, ?>> existingJoins, Join<?, ?> join, Field pathField) {
        var optionalJoin = existingJoins.stream()
                .filter(j -> pathField.getDeclaringClass().equals(j.getAttribute().getDeclaringType().getJavaType()) && j.getAttribute().getName().equals(pathField.getName()) && j.getJoinType().equals(JoinType.INNER))
                .findFirst();

        if (optionalJoin.isPresent()) {
            join = optionalJoin.get();
        } else {
            join = join == null ?
                    root.join(pathField.getName(), JoinType.INNER) :
                    join.join(pathField.getName(), JoinType.INNER);
        }
        return join;
    }

    private Predicate buildPredicate(QueryFilterCriteria query, CriteriaBuilder criteriaBuilder, Field queryField, Path<T> path) throws IllegalAccessException {
        var jpaOperation = getFieldAnnotation(queryField, JpaQueryOperation.class);
        var jpaOperationEnum = JpaQueryOperationEnum.EQUAL;
        var userJpaOperation = query.getFieldsOperations().get(queryField.getName());

        if (userJpaOperation != null) {
            jpaOperationEnum = userJpaOperation;
        } else if (jpaOperation.isPresent()) {
            jpaOperationEnum = jpaOperation.get().value();
        }

        return buildCriteria(jpaOperationEnum, query, criteriaBuilder, queryField, path);
    }

    private Predicate buildCriteria(JpaQueryOperationEnum jpaOperationEnum, QueryFilterCriteria query, CriteriaBuilder criteriaBuilder, Field queryField, Path<T> path) throws IllegalAccessException {
        Predicate predicate;
        switch (jpaOperationEnum) {
            case IN:
                if (!isCollectionType(queryField.get(query).getClass())) {
                    throw new InvalidFieldTypeException("Operation is IN, but field type is not a collection");
                }
                predicate = path.in((Collection<?>) queryField.get(query));
                break;
            case LIKE:
                if (!(queryField.get(query) instanceof String || queryField.get(query) instanceof Number)) {
                    throw new InvalidFieldTypeException("Operation is LIKE, but field type is not a string or a number");
                }
                predicate = criteriaBuilder.like(path.as(String.class), "%" + queryField.get(query).toString() + "%");
                break;
            case NOT_EQUAL:
                predicate = criteriaBuilder.notEqual(path, queryField.get(query));
                break;
            case LESS_THAN:
                predicate = buildLessThanPredicate(criteriaBuilder, path, queryField.get(query));
                break;
            case GREATER_THAN:
                predicate = buildGreaterThanPredicate(criteriaBuilder, path, queryField.get(query));
                break;
            case EQUAL:
            default:
                predicate = criteriaBuilder.equal(path, queryField.get(query));
                break;
        }
        return predicate;
    }

    @SuppressWarnings("unchecked")
    private <N extends Number & Comparable<N>> Predicate buildLessThanPredicate(CriteriaBuilder criteriaBuilder, Path<T> path, Object fieldValue) {
        if (!(fieldValue instanceof Number)) {
            throw new InvalidFieldTypeException("Operation is LESS_THAN, but field type is not a number");
        }

        return criteriaBuilder.lessThan(path.as((Class<N>) fieldValue.getClass()), (N) fieldValue);
    }

    @SuppressWarnings("unchecked")
    private <N extends Number & Comparable<N>> Predicate buildGreaterThanPredicate(CriteriaBuilder criteriaBuilder, Path<T> path, Object fieldValue) {
        if (!(fieldValue instanceof Number)) {
            throw new InvalidFieldTypeException("Operation is GREATER_THAN, but field type is not a number");
        }

        return criteriaBuilder.greaterThan(path.as((Class<N>) fieldValue.getClass()), (N) fieldValue);
    }

    private List<Predicate> buildPredicatesInternal(Class<?> entity, QueryFilterCriteria query, Root<T> root, CriteriaBuilder criteriaBuilder, Set<Join<?, ?>> existingJoins) throws IllegalAccessException {
        var queryFields = getRecursiveDeclaredFields(query.getClass()).stream()
                .filter(field -> getFieldAnnotation(field, JpaQueryIgnore.class).isEmpty())
                .collect(Collectors.toList());

        var predicates = new ArrayList<Predicate>();
        for (var queryField : queryFields) {
            if (queryField.get(query) == null || getFieldAnnotation(queryField, JpaQueryFieldsOperations.class).isPresent()) continue;

            String[] entityFieldNames;
            var queryFieldAnnotation = getFieldAnnotation(queryField, JpaQueryPath.class);
            if (queryFieldAnnotation.isPresent() && queryFieldAnnotation.get().value().length > 0) {
                entityFieldNames = queryFieldAnnotation.get().value();
            } else {
                entityFieldNames = queryField.getName().split("_");
            }

            var pathFields = new ArrayList<Field>();
            Deque<String> depthFieldNames = new ArrayDeque<>();
            Arrays.stream(entityFieldNames).forEach(depthFieldNames::push);
            var searchedFieldName = depthFieldNames.pop();

            var fieldNamesIterator = depthFieldNames.descendingIterator();
            var currentEntity = entity;
            while (fieldNamesIterator.hasNext()) {
                var fieldName = fieldNamesIterator.next();

                try {
                    var entityField = getRecursiveDeclaredField(currentEntity, fieldName);
                    if (!isNotDomainColumn(entityField)) {
                        throw new NoSuchFieldException();
                    }

                    pathFields.add(entityField);
                    currentEntity = getFieldType(entityField);
                } catch (NoSuchFieldException e) {
                    throw new RuntimeException(e);
                }
            }

            Join<?, ?> join = null;
            for (var pathField : pathFields) {
                join = buildPathJoin(root, existingJoins, join, pathField);
                existingJoins.add(join);
            }

            Path<T> actualPath = join == null ? root.get(searchedFieldName) : join.get(searchedFieldName);
            predicates.add(buildPredicate(query, criteriaBuilder, queryField, actualPath));
        }

        return predicates;
    }

    default List<Predicate> buildPredicates(QueryFilterCriteria query, Root<T> root, CriteriaBuilder criteriaBuilder) {
        try {
            return buildPredicatesInternal(getType(), query, root, criteriaBuilder, new HashSet<>());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @param jpaQuery Filter criteria object
     * @param pageable pageable object for your filter
     * @return Page of result
     */
    default Page<T> findByQuery(QueryFilterCriteria jpaQuery, Pageable pageable) {
        return findByQuery(jpaQuery, pageable, null, List.of());
    }

    default Page<T> findByQuery(QueryFilterCriteria jpaQuery, Pageable pageable, Sort sort) {
        return findByQuery(jpaQuery, pageable, sort, List.of());
    }

    /**
     * @param jpaQuery Filter criteria object
     * @param pageable pageable object for your filter
     * @param additionalPredicates additional custom predicates to be added to the filter
     * @return Page of result
     */
    default Page<T> findByQuery(QueryFilterCriteria jpaQuery, Pageable pageable, Sort sort, List<Predicate> additionalPredicates) {
        log.info("Start repository: multi criteria search by query {} with page {} and size {}", jpaQuery, pageable.getPageNumber(), pageable.getPageSize());
        Specification<T> specification = (root, query, criteriaBuilder) -> {
            var predicates = buildPredicates(jpaQuery, root, criteriaBuilder);
            predicates.addAll(additionalPredicates);
            if (sort != null) {
                setSortingCriteria(root, criteriaBuilder, sort);
            }
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };

        log.info("End repository: multi criteria search by query {} with page {} and size {}", jpaQuery, pageable.getPageNumber(), pageable.getPageSize());
        return findAll(specification, pageable);
    }

    private void setSortingCriteria(Root<?> root, CriteriaBuilder criteriaBuilder, Sort sort) {
        for (Sort.Order sortItem : sort) {
            if (sortItem.isAscending()) {
                criteriaBuilder.asc(root.get(sortItem.getProperty()));
            } else {
                criteriaBuilder.desc(root.get(sortItem.getProperty()));
            }
        }
    }

    /**
     * @param jpaQuery Filter criteria object
     * @param page page number
     * @param size page size
     * @return Page of result
     */
    default Page<T> findByQuery(QueryFilterCriteria jpaQuery, Integer page, Integer size) {
        var pageable = PageRequest.of(page, size);
        return findByQuery(jpaQuery, pageable);
    }

    default Long countByQuery(QueryFilterCriteria jpaQuery) {
        log.info("Start repository: Get count by query : {}", jpaQuery);
        Specification<T> specifications = (root, query, criteriaBuilder) -> {
            var predicates = buildPredicates(jpaQuery, root, criteriaBuilder);
            return criteriaBuilder.and(predicates.toArray(Predicate[]::new));
        };
        var output = count(specifications);
        log.info("End repository: Get count by query : {}", jpaQuery);
        return output;
    }
}
