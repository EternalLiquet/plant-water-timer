package com.eternalliquet.plantcare.garden;

import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/garden")
class GardenController {
  private final GardenService garden;
  private final PhotoService photos;

  GardenController(GardenService garden, PhotoService photos) {
    this.garden = garden;
    this.photos = photos;
  }

  @GetMapping("/plants")
  List<GardenService.Plant> list(Authentication a) {
    return garden.list(GardenSecurity.owner(a));
  }

  @PostMapping("/plants")
  ResponseEntity<GardenService.Plant> create(
      Authentication a, @RequestBody GardenService.Create r) {
    return ResponseEntity.status(201).body(garden.create(GardenSecurity.owner(a), r));
  }

  @PatchMapping("/plants/{id}")
  GardenService.Plant edit(
      Authentication a, @PathVariable UUID id, @RequestBody GardenService.Edit r) {
    return garden.edit(GardenSecurity.owner(a), id, r);
  }

  @DeleteMapping("/plants/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(Authentication a, @PathVariable UUID id) {
    garden.delete(GardenSecurity.owner(a), id);
  }

  @PostMapping("/plants/{id}/water")
  GardenService.Plant water(
      Authentication a, @PathVariable UUID id, @RequestBody GardenService.WaterRequest r) {
    return garden.water(GardenSecurity.owner(a), id, r);
  }

  @PostMapping("/plants/{id}/water/{event}/undo")
  GardenService.Plant undo(Authentication a, @PathVariable UUID id, @PathVariable UUID event) {
    return garden.undo(GardenSecurity.owner(a), id, event);
  }

  @GetMapping("/plants/{id}/history")
  List<GardenService.Water> history(Authentication a, @PathVariable UUID id) {
    return garden.history(GardenSecurity.owner(a), id);
  }

  @PostMapping("/photos")
  PhotoService.Upload photo(Authentication a, @RequestParam("file") MultipartFile file)
      throws java.io.IOException {
    return photos.upload(GardenSecurity.owner(a), file.getBytes());
  }

  @GetMapping("/photos/{id}")
  ResponseEntity<byte[]> photo(Authentication a, @PathVariable UUID id) throws java.io.IOException {
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .cacheControl(CacheControl.noStore())
        .body(photos.read(GardenSecurity.owner(a), id));
  }

  @DeleteMapping("/photos/{id}")
  void discard(Authentication a, @PathVariable UUID id) throws java.io.IOException {
    photos.discard(GardenSecurity.owner(a), id);
  }
}
