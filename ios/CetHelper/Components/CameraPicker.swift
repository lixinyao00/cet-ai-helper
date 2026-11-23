import PhotosUI
import SwiftUI
import UIKit

struct CameraPickerView: UIViewControllerRepresentable {
    let onImagePicked: (UIImage) -> Void
    let onCancel: () -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(onImagePicked: onImagePicked, onCancel: onCancel)
    }

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = UIImagePickerController.isSourceTypeAvailable(.camera) ? .camera : .photoLibrary
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ uiViewController: UIImagePickerController, context: Context) {
        context.coordinator.onImagePicked = onImagePicked
        context.coordinator.onCancel = onCancel
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        var onImagePicked: (UIImage) -> Void
        var onCancel: () -> Void

        init(onImagePicked: @escaping (UIImage) -> Void, onCancel: @escaping () -> Void) {
            self.onImagePicked = onImagePicked
            self.onCancel = onCancel
        }

        func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            if let image = info[.originalImage] as? UIImage {
                onImagePicked(image)
            } else {
                onCancel()
            }
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            onCancel()
        }
    }
}

struct PageImportPickerModifier: ViewModifier {
    @Bindable var viewModel: PageImportViewModel
    @State private var selectedPhotoItem: PhotosPickerItem?
    @State private var isLoadingPhoto = false

    func body(content: Content) -> some View {
        let isCameraPresented = Binding<Bool>(
            get: { viewModel.pickerSource == .camera },
            set: { if !$0 && viewModel.pickerSource == .camera { viewModel.pickerSource = nil } }
        )
        let isPhotosPresented = Binding<Bool>(
            get: { viewModel.pickerSource == .photoLibrary },
            set: { presented in
                if !presented && viewModel.pickerSource == .photoLibrary {
                    viewModel.pickerSource = nil
                    if selectedPhotoItem == nil && !isLoadingPhoto {
                        viewModel.pickerCancelled()
                    }
                }
            }
        )

        content
            .fullScreenCover(isPresented: isCameraPresented) {
                CameraPickerView(
                    onImagePicked: { image in
                        viewModel.pickerSource = nil
                        viewModel.didPick(image: image)
                    },
                    onCancel: {
                        viewModel.pickerSource = nil
                        viewModel.pickerCancelled()
                    }
                )
                .ignoresSafeArea()
            }
            .photosPicker(
                isPresented: isPhotosPresented,
                selection: $selectedPhotoItem,
                matching: .images
            )
            .onChange(of: selectedPhotoItem) { _, newItem in
                guard let newItem else { return }
                isLoadingPhoto = true
                Task { @MainActor in
                    let data = try? await newItem.loadTransferable(type: Data.self)
                    selectedPhotoItem = nil
                    isLoadingPhoto = false
                    viewModel.pickerSource = nil
                    if let data {
                        viewModel.didPick(imageData: data)
                    } else {
                        viewModel.pickerCancelled()
                    }
                }
            }
    }
}

extension View {
    func pageImportPickers(viewModel: PageImportViewModel) -> some View {
        modifier(PageImportPickerModifier(viewModel: viewModel))
    }
}
