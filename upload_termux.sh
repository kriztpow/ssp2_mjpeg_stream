#!/data/data/com.termux/files/usr/bin/bash

# Colores para output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Función para mostrar mensajes de error
error_msg() {
    echo -e "${RED}Error: $1${NC}"
}

# Función para mostrar mensajes de éxito
success_msg() {
    echo -e "${GREEN}$1${NC}"
}

# Función para mostrar advertencias
warning_msg() {
    echo -e "${YELLOW}$1${NC}"
}

# Función para mostrar información
info_msg() {
    echo -e "${BLUE}$1${NC}"
}

# Verificar instalación de Git
if ! command -v git &> /dev/null; then
    warning_msg "Git no está instalado. Instalando..."
    pkg update && pkg install git -y
    success_msg "Git instalado correctamente."
fi

# Función para verificar si es un repositorio Git
is_git_repo() {
    git rev-parse --is-inside-work-tree > /dev/null 2>&1
}

# Función para sincronizar archivos desde Descargas
sync_from_downloads() {
    local source_dir="/sdcard/Download/ssp2_mjpeg_stream"
    local target_dir="$HOME/ssp2_mjpeg_stream"
    
    # Verificar si existe el directorio fuente
    if [ ! -d "$source_dir" ]; then
        error_msg "No se encuentra el directorio: $source_dir"
        return 1
    fi
    
    warning_msg "Sincronizando archivos desde Descargas..."
    
    # Crear directorio de destino si no existe
    mkdir -p "$target_dir"
    
    # Usar rsync para sincronizar (mejor que cp para mantener permisos y actualizar solo lo necesario)
    if command -v rsync &> /dev/null; then
        rsync -av --delete "$source_dir/" "$target_dir/" 2>/dev/null
    else
        # Si no hay rsync, usar cp con opciones para preservar y actualizar
        cp -ru "$source_dir"/* "$target_dir"/ 2>/dev/null || true
        cp -ru "$source_dir"/.[^.]* "$target_dir"/ 2>/dev/null || true
    fi
    
    success_msg "Archivos sincronizados correctamente a: $target_dir"
    cd "$target_dir" || exit 1
}

# Función para configurar repositorio nuevo
setup_new_repo() {
    read -p "GitHub repo URL (https://github.com/USER/REPO.git): " REPO
    git init
    git add .
    git commit -m "Initial commit - ScreenShare final"
    git branch -M main
    git remote add origin "${REPO}"
    
    # Intentar hacer push y manejar errores
    if ! git push -u origin main; then
        warning_msg "El push inicial falló. Intentando forzar el push..."
        git push -f -u origin main
    fi
}

# Función para resolver conflictos de Git
resolve_conflicts() {
    info_msg "Se han detectado conflictos de Git."
    echo "Opciones disponibles:"
    echo "1. Abortar y resolver manualmente (recomendado)"
    echo "2. Forzar push (sobrescribirá el repositorio remoto)"
    echo "3. Usar versión local (descarta cambios remotos)"
    echo "4. Usar versión remota (descarta cambios locales)"
    read -p "Selecciona una opción [1-4]: " conflict_choice

    case $conflict_choice in
        1)
            error_msg "Resuelve los conflictos manualmente:"
            echo "1. Edita los archivos con conflictos (busca '<<<<<<<', '=======', '>>>>>>>')"
            echo "2. Elimina las marcas de conflicto y decide qué código mantener"
            echo "3. Ejecuta: git add [archivos_resueltos]"
            echo "4. Ejecuta: git rebase --continue"
            echo "5. Luego vuelve a ejecutar este script"
            exit 1
            ;;
        2)
            warning_msg "Forzando push para sobrescribir el repositorio remoto..."
            git push -f
            success_msg "Push forzado completado."
            ;;
        3)
            warning_msg "Usando versión local (descarta cambios remotos)..."
            git checkout --ours .
            git add .
            git rebase --continue
            git push
            success_msg "Conflictos resueltos usando versión local."
            ;;
        4)
            warning_msg "Usando versión remota (descarta cambios locales)..."
            git checkout --theirs .
            git add .
            git rebase --continue
            git push
            success_msg "Conflictos resueltos usando versión remota."
            ;;
        *)
            error_msg "Opción inválida. Abortando."
            exit 1
            ;;
    esac
}

# Función para actualizar repositorio existente
update_existing_repo() {
    # Primero, obtener los cambios remotos
    warning_msg "Obteniendo cambios del repositorio remoto..."
    git fetch origin

    # Verificar si hay cambios locales
    if [ -z "$(git status --porcelain)" ]; then
        warning_msg "No hay cambios locales para subir."
        # Aún así intentar pull para estar actualizado
        if git pull --rebase origin main; then
            success_msg "Repositorio actualizado (sin cambios locales)."
        else
            resolve_conflicts
        fi
        return 0
    fi

    # Si hay cambios locales, proceder con commit y push
    git add .
    
    # Commit con mensaje personalizado
    read -p "Mensaje del commit (default: 'Actualización automática'): " COMMIT_MSG
    COMMIT_MSG=${COMMIT_MSG:-"Actualización automática"}
    git commit -m "${COMMIT_MSG}"
    
    # Intentar hacer push
    if git push; then
        success_msg "Cambios subidos correctamente."
    else
        warning_msg "Falló el push. Intentando integrar cambios remotos..."
        
        # Intentar hacer pull con rebase
        if git pull --rebase origin main; then
            success_msg "Cambios integrados correctamente."
            git push
            success_msg "Cambios subidos después de integrar cambios remotos."
        else
            resolve_conflicts
        fi
    fi
}

# Menú principal
while true; do
    echo ""
    echo "========================================"
    echo "    MENÚ PRINCIPAL - SUBIR A GITHUB"
    echo "========================================"
    echo "1. Sincronizar desde Descargas y subir nuevo proyecto"
    echo "2. Sincronizar desde Descargas y actualizar repositorio existente"
    echo "3. Solo subir nuevo proyecto (desde directorio actual)"
    echo "4. Solo actualizar repositorio existente (desde directorio actual)"
    echo "5. Solo sincronizar desde Descargas (sin subir a GitHub)"
    echo "6. Salir"
    echo "========================================"
    read -p "Selecciona una opción [1-6]: " main_choice

    case $main_choice in
        1)
            if sync_from_downloads; then
                setup_new_repo
                success_msg "Proyecto sincronizado y subido correctamente."
            fi
            ;;
        2)
            if sync_from_downloads; then
                if is_git_repo; then
                    update_existing_repo
                else
                    warning_msg "Esta carpeta no es un repositorio Git."
                    read -p "¿Deseas convertirlo en un nuevo repositorio? (s/n): " convert_repo
                    if [[ $convert_repo == "s" || $convert_repo == "S" ]]; then
                        setup_new_repo
                    else
                        warning_msg "Operación cancelada."
                    fi
                fi
            fi
            ;;
        3)
            if is_git_repo; then
                warning_msg "Ya existe un repositorio Git aquí. Usa la opción 4 para actualizar."
            else
                setup_new_repo
                success_msg "Proyecto subido correctamente."
            fi
            ;;
        4)
            if is_git_repo; then
                update_existing_repo
            else
                error_msg "Esta carpeta no es un repositorio Git. Usa la opción 3 para crear uno nuevo."
            fi
            ;;
        5)
            sync_from_downloads
            ;;
        6)
            success_msg "¡Hasta pronto!"
            exit 0
            ;;
        *)
            error_msg "Opción inválida. Por favor, selecciona una opción del 1 al 6."
            ;;
    esac
    
    read -p "Presiona Enter para continuar..."
done
