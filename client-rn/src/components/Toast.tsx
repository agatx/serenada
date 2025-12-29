import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';

export type ToastType = 'success' | 'error' | 'warning' | 'info';

interface ToastProps {
  type: ToastType;
  message: string;
  onClose: () => void;
}

const backgroundByType: Record<ToastType, string> = {
  success: '#1f6f3f',
  error: '#7a1a1a',
  warning: '#7a5b1a',
  info: '#1a3d7a',
};

export const Toast: React.FC<ToastProps> = ({ type, message, onClose }) => {
  return (
    <View style={[styles.toast, { backgroundColor: backgroundByType[type] }]}> 
      <Text style={styles.message}>{message}</Text>
      <Pressable onPress={onClose} style={styles.closeButton}>
        <Text style={styles.closeText}>×</Text>
      </Pressable>
    </View>
  );
};

const styles = StyleSheet.create({
  toast: {
    minWidth: 240,
    maxWidth: 320,
    paddingVertical: 12,
    paddingHorizontal: 14,
    borderRadius: 12,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 4 },
    shadowOpacity: 0.3,
    shadowRadius: 6,
    elevation: 6,
    marginBottom: 12,
  },
  message: {
    color: '#fff',
    fontSize: 14,
    flex: 1,
    paddingRight: 8,
  },
  closeButton: {
    paddingHorizontal: 6,
    paddingVertical: 2,
  },
  closeText: {
    color: '#fff',
    fontSize: 18,
    lineHeight: 18,
  },
});
