import React from 'react';
import { SafeAreaView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { v4 as uuidv4 } from 'uuid';

type HomeScreenProps = {
  onStartCall: (roomId: string) => void;
};

const HomeScreen: React.FC<HomeScreenProps> = ({ onStartCall }) => {

  const startCall = () => {
    const roomId = uuidv4();
    onStartCall(roomId);
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <View style={styles.container}>
        <Text style={styles.title}>Connected</Text>
        <Text style={styles.subtitle}>Simple, instant video calls for everyone.</Text>
        <Text style={styles.subtitle}>No accounts, no downloads.</Text>

        <TouchableOpacity style={styles.primaryButton} onPress={startCall}>
          <Text style={styles.primaryButtonText}>Start Call</Text>
        </TouchableOpacity>
      </View>
    </SafeAreaView>
  );
};

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#0b0f14',
  },
  container: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  title: {
    fontSize: 44,
    fontWeight: '700',
    color: '#f2f2f2',
    letterSpacing: 0.5,
  },
  subtitle: {
    fontSize: 16,
    color: '#b8c0cc',
    textAlign: 'center',
    marginTop: 6,
  },
  primaryButton: {
    marginTop: 24,
    backgroundColor: '#2563eb',
    paddingVertical: 14,
    paddingHorizontal: 28,
    borderRadius: 999,
  },
  primaryButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
});

export default HomeScreen;
