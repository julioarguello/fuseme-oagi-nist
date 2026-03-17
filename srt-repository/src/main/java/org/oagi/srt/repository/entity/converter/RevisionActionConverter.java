package org.oagi.srt.repository.entity.converter;

import org.oagi.srt.repository.entity.RevisionAction;

import javax.persistence.AttributeConverter;

// (JAF), 20260317, Removed @Converter(autoApply=true): workaround for Hibernate 5.0.x duplicate converter registration bug
public class RevisionActionConverter
        implements AttributeConverter<RevisionAction, Integer> {

    @Override
    public Integer convertToDatabaseColumn(RevisionAction attribute) {
        return (attribute == null) ? 0 : attribute.getValue();
    }

    @Override
    public RevisionAction convertToEntityAttribute(Integer dbData) {
        if (dbData == null || dbData == 0) {
            return null;
        }
        return RevisionAction.valueOf(dbData);
    }
}