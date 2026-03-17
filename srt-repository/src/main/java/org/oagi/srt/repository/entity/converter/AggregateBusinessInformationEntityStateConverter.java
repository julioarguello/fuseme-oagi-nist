package org.oagi.srt.repository.entity.converter;

import org.oagi.srt.repository.entity.AggregateBusinessInformationEntityState;

import javax.persistence.AttributeConverter;

// (JAF), 20260317, Removed @Converter(autoApply=true): workaround for Hibernate 5.0.x duplicate converter registration bug
public class AggregateBusinessInformationEntityStateConverter
        implements AttributeConverter<AggregateBusinessInformationEntityState, Integer> {

    @Override
    public Integer convertToDatabaseColumn(AggregateBusinessInformationEntityState attribute) {
        return (attribute == null) ? 0 : attribute.getValue();
    }

    @Override
    public AggregateBusinessInformationEntityState convertToEntityAttribute(Integer dbData) {
        if (dbData == null || dbData == 0) {
            return null;
        }
        return AggregateBusinessInformationEntityState.valueOf(dbData);
    }
}