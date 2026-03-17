package org.oagi.srt.repository.entity.converter;

import org.oagi.srt.repository.entity.CoreComponentState;

import javax.persistence.AttributeConverter;

// (JAF), 20260317, Removed @Converter(autoApply=true): workaround for Hibernate 5.0.x duplicate converter registration bug
public class CoreComponentStateConverter
        implements AttributeConverter<CoreComponentState, Integer> {

    @Override
    public Integer convertToDatabaseColumn(CoreComponentState attribute) {
        return (attribute == null) ? 0 : attribute.getValue();
    }

    @Override
    public CoreComponentState convertToEntityAttribute(Integer dbData) {
        if (dbData == null || dbData <= 0) {
            return null;
        }
        return CoreComponentState.valueOf(dbData);
    }
}