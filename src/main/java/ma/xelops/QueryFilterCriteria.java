package ma.xelops;

import ma.xelops.annotations.JpaQueryFieldsOperations;
import ma.xelops.enums.JpaQueryOperationEnum;
import ma.xelops.exceptions.ConflictingFieldsException;
import ma.xelops.exceptions.InvalidFieldTypeException;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

public interface QueryFilterCriteria {
    @SuppressWarnings("unchecked")
    default Map<String, JpaQueryOperationEnum> getFieldsOperations() {
        var operationsFields = Arrays.stream(getClass().getDeclaredFields())
                .filter(field -> field.isAnnotationPresent(JpaQueryFieldsOperations.class))
                .collect(Collectors.toList());

        if (operationsFields.size() > 1) {
            throw new ConflictingFieldsException(
                    String.format(
                            "Conflicting fields '%s' annotated as Fields Operations",
                            operationsFields.stream()
                                    .map(Field::getName)
                                    .collect(Collectors.toList())
                    )
            );
        }

        var operationsField = operationsFields.stream()
                .findFirst();

        if (operationsField.isPresent()) {
            try {
                if (operationsField.get().getType() != Map.class) {
                    throw new InvalidFieldTypeException(
                            String.format(
                                    "Field '%s' annotated as Fields Operations but is not instance of type '%s' instead of type '%s'",
                                    operationsField.get().getName(),
                                    Map.class,
                                    operationsField.get().getType()
                            )
                    );
                }

                if (!(operationsField.get().getGenericType() instanceof ParameterizedType)) {
                    throw new InvalidFieldTypeException(
                            String.format(
                                    "Field '%s' annotated as Fields Operations is of type '%s' but raw types are not supported",
                                    operationsField.get().getName(),
                                    Map.class
                            )
                    );
                }

                ParameterizedType type = (ParameterizedType) operationsField.get().getGenericType();
                Type key = type.getActualTypeArguments()[0];
                Type value = type.getActualTypeArguments()[1];

                if (key != String.class) {
                    throw new InvalidFieldTypeException(
                            String.format(
                                    "Field '%s' annotated as Fields Operations but key is not of type '%s' instead of type '%s'",
                                    operationsField.get().getName(),
                                    String.class,
                                    key
                            )
                    );
                }

                if (value != JpaQueryOperationEnum.class) {
                    throw new InvalidFieldTypeException(
                            String.format(
                                    "Field '%s' annotated as Fields Operations but value is not of type '%s' instead of type '%s'",
                                    operationsField.get().getName(),
                                    JpaQueryOperationEnum.class,
                                    value
                            )
                    );
                }

                operationsField.get().setAccessible(true);
                var operations = (Map<String, JpaQueryOperationEnum>) operationsField.get().get(this);
                return Objects.requireNonNullElseGet(operations, HashMap::new);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }

        return new HashMap<>();
    }
}
