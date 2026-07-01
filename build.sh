#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

open_folder() {
    local path="$1"
    if command -v xdg-open &>/dev/null; then
        xdg-open "$path"
    elif command -v open &>/dev/null; then
        open "$path"
    else
        echo "Open $path manually."
    fi
}

echo ""
echo "  =========================================="
echo "   AniMurglar Build"
echo "  =========================================="
echo ""
echo "  [1] Build app (no ProGuard)"
echo "  [2] Build ZIP (no ProGuard)"
echo "  [3] Build ZIP (with ProGuard)"
echo ""
read -rp "Select: " choice

case "$choice" in
    1)
        echo ""
        echo "Building AniMurglar (no ProGuard)..."
        ./gradlew createDistributable
        ;;
    2)
        echo ""
        echo "Building AniMurglar ZIP (no ProGuard)..."
        ./gradlew zipAppImage
        ;;
    3)
        echo ""
        echo "Building AniMurglar ZIP (with ProGuard)..."
        ./gradlew zipReleaseAppImage
        ;;
    *)
        echo "Invalid choice."
        read -rp "Press Enter to exit..."
        exit 1
        ;;
esac

if [ $? -ne 0 ]; then
    echo ""
    echo "Build failed."
    read -rp "Press Enter to exit..."
    exit $?
fi

echo ""
echo "Build succeeded."

case "$choice" in
    1)
        open_folder "build/compose/binaries/main/app/AniMurglar"
        ;;
    2|3)
        open_folder "build/artifacts"
        ;;
esac

echo ""
read -rp "Press Enter to exit..."
