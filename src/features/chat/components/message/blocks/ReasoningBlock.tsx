import React, { useState } from 'react';
import { View, TouchableOpacity, LayoutAnimation } from 'react-native';
import { useMessageContext } from '../MessageContext';
import { Typography } from '../../../../../components/ui';
import { Brain, ChevronDown, ChevronUp } from 'lucide-react-native';

export const ReasoningBlock: React.FC = React.memo(() => {
  const { message, isDark, colors, t } = useMessageContext();
  const [isExpanded, setIsExpanded] = useState(true);

  if (!message.reasoning) return null;

  const toggleExpanded = () => {
    LayoutAnimation.configureNext(LayoutAnimation.Presets.easeInEaseOut);
    setIsExpanded(!isExpanded);
  };

  return (
    <View style={{
      marginVertical: 8,
      backgroundColor: isDark ? 'rgba(255,255,255,0.03)' : 'rgba(0,0,0,0.02)',
      borderRadius: 12,
      borderLeftWidth: 2,
      borderLeftColor: colors[500],
      overflow: 'hidden'
    }}>
      <TouchableOpacity 
        onPress={toggleExpanded}
        activeOpacity={0.7}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          padding: 12,
          gap: 8
        }}
      >
        <Brain size={16} color={isDark ? '#a1a1aa' : '#71717a'} />
        <Typography style={{ 
          flex: 1, 
          fontSize: 13, 
          fontWeight: '600',
          color: isDark ? '#a1a1aa' : '#71717a'
        }}>
          {t?.chat?.thinking || 'Thinking Process'}
        </Typography>
        {isExpanded ? (
          <ChevronUp size={16} color={isDark ? '#a1a1aa' : '#71717a'} />
        ) : (
          <ChevronDown size={16} color={isDark ? '#a1a1aa' : '#71717a'} />
        )}
      </TouchableOpacity>
      
      {isExpanded && (
        <View style={{ paddingHorizontal: 12, paddingBottom: 12, paddingTop: 4 }}>
          <Typography style={{ 
            fontSize: 13, 
            lineHeight: 20,
            color: isDark ? '#71717a' : '#a1a1aa',
            fontStyle: 'italic'
          }}>
            {message.reasoning}
          </Typography>
        </View>
      )}
    </View>
  );
});
