import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import LanguageDetector from 'i18next-browser-languagedetector';

const resources = {
    en: {
        translation: {
            "app_title": "Connected",
            "app_subtitle_1": "Simple, instant video calls for everyone.",
            "app_subtitle_2": "No accounts, no downloads.",
            "start_call": "Start Call",
            "ready_to_join": "Ready to join?",
            "room_id": "Room ID:",
            "camera_off": "Camera Off",
            "join_call": "Join Call",
            "connecting": "Connecting...",
            "copy_link": "Copy Link",
            "waiting_message": "Waiting for someone to join...",
            "waiting_message_person": "Connecting...",
            "copy_link_share": "Copy Link to Share",
            "recent_calls": "Recent calls",
            "date_time": "Date & Time",
            "duration": "Duration",
            "someone_waiting": "Someone is waiting",
            "room_full": "Room is full",
            "toast_camera_error": "Could not access camera/microphone.",
            "toast_link_copied": "Link copied to clipboard!"
        }
    },
    ru: {
        translation: {
            "app_title": "Connected",
            "app_subtitle_1": "Простые, мгновенные видеозвонки для всех.",
            "app_subtitle_2": "Без регистрации, без скачивания.",
            "start_call": "Начать звонок",
            "ready_to_join": "Готовы присоединиться?",
            "room_id": "ID комнаты:",
            "camera_off": "Камера выключена",
            "join_call": "Присоединиться",
            "connecting": "Подключение...",
            "copy_link": "Копировать ссылку",
            "waiting_message": "Ожидание собеседника...",
            "waiting_message_person": "Подключение...",
            "copy_link_share": "Копировать ссылку для отправки",
            "recent_calls": "Недавние звонки",
            "date_time": "Дата и время",
            "duration": "Длительность",
            "someone_waiting": "Кто-то ждет",
            "room_full": "Комната заполнена",
            "toast_camera_error": "Не удалось получить доступ к камере/микрофону.",
            "toast_link_copied": "Ссылка скопирована в буфер обмена!"
        }
    },
    es: {
        translation: {
            "app_title": "Connected",
            "app_subtitle_1": "Videollamadas simples e instantáneas para todos.",
            "app_subtitle_2": "Sin cuentas, sin descargas.",
            "start_call": "Iniciar llamada",
            "ready_to_join": "¿Listo para unirte?",
            "room_id": "ID de sala:",
            "camera_off": "Cámara apagada",
            "join_call": "Unirse a la llamada",
            "connecting": "Conectando...",
            "copy_link": "Copiar enlace",
            "waiting_message": "Esperando a que alguien se una...",
            "waiting_message_person": "Conectando...",
            "copy_link_share": "Copiar enlace para compartir",
            "recent_calls": "Llamadas recientes",
            "date_time": "Fecha y hora",
            "duration": "Duración",
            "someone_waiting": "Alguien está esperando",
            "room_full": "La sala está llena",
            "toast_camera_error": "No se pudo acceder a la cámara/micrófono.",
            "toast_link_copied": "¡Enlace copiado al portapapeles!"
        }
    },
    fr: {
        translation: {
            "app_title": "Connected",
            "app_subtitle_1": "Des appels vidéo simples et instantanés pour tous.",
            "app_subtitle_2": "Pas de compte, pas de téléchargement.",
            "start_call": "Démarrer un appel",
            "ready_to_join": "Prêt à rejoindre ?",
            "room_id": "ID de la salle :",
            "camera_off": "Caméra désactivée",
            "join_call": "Rejoindre l'appel",
            "connecting": "Connexion...",
            "copy_link": "Copier le lien",
            "waiting_message": "En attente de quelqu'un...",
            "waiting_message_person": "Connexion...",
            "copy_link_share": "Copier le lien pour partager",
            "recent_calls": "Appels récents",
            "date_time": "Date et heure",
            "duration": "Durée",
            "someone_waiting": "Quelqu'un attend",
            "room_full": "La salle est pleine",
            "toast_camera_error": "Impossible d'accéder à la caméra/au microphone.",
            "toast_link_copied": "Lien copié dans le presse-papiers !"
        }
    }
};

i18n
    .use(LanguageDetector)
    .use(initReactI18next)
    .init({
        resources,
        fallbackLng: 'en',
        interpolation: {
            escapeValue: false
        }
    });

export default i18n;
